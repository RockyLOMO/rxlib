package org.rx.core;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ClassUtils;
import org.apache.commons.lang3.reflect.ConstructorUtils;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.commons.lang3.reflect.MethodUtils;
import org.apache.commons.lang3.reflect.TypeUtils;
import org.rx.annotation.ErrorCode;
import org.rx.bean.*;
import org.rx.core.cache.MemoryCache;
import org.rx.exception.ApplicationException;
import org.rx.exception.InvalidException;
import org.rx.util.Lazy;
import org.rx.util.function.BiFunc;
import org.rx.util.function.TripleFunc;
import org.springframework.core.io.InputStreamSource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.InputStream;
import java.io.Serializable;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.rx.core.Constants.NON_RAW_TYPES;
import static org.rx.core.Constants.NON_UNCHECKED;
import static org.rx.core.Extends.*;
import static org.rx.core.Sys.*;

@SuppressWarnings(NON_UNCHECKED)
@Slf4j
public class Reflects extends ClassUtils {
    //region NestedTypes
    @RequiredArgsConstructor
    public static class PropertyNode implements Serializable {
        private static final long serialVersionUID = 3680733077204898075L;
        public final String propertyName;
        public final Method setter;
        public final Method getter;
    }

    @RequiredArgsConstructor
    static class ConvertBean<TS, TT> {
        final Class<TS> baseFromType;
        final Class<TT> toType;
        final TripleFunc<TS, Class<TT>, TT> converter;
    }

    public interface ClassTracer {
        Class<?>[] getClassTrace();

        Class<?> getClassTrace(int depth);
    }

    static class SecurityManagerEx extends SecurityManager implements ClassTracer {
        static final SecurityManagerEx INSTANCE = new SecurityManagerEx();

        public Class<?>[] getClassTrace() {
            return getClassContext();
        }

        public Class<?> getClassTrace(int depth) {
            return getClassContext()[1 + depth];
        }
    }
    //endregion

    public static final Linq<String> COLLECTION_WRITE_METHOD_NAMES = Linq.from("add", "remove", "addAll", "removeAll", "removeIf", "retainAll", "clear"),
            List_WRITE_METHOD_NAMES = COLLECTION_WRITE_METHOD_NAMES.union(Arrays.toList("replaceAll", "set"));
    public static final Set<Method> OBJECT_METHODS = Collections.unmodifiableSet(new HashSet<>(Arrays.toList(Object.class.getMethods())));
    public static final ClassTracer CLASS_TRACER = new SecurityManagerEx();
    static final String M_0 = "close", CHANGE_TYPE_METHOD = "valueOf";
    static final String GET_PROPERTY = "get", GET_BOOL_PROPERTY = "is", SET_PROPERTY = "set";
    static final int LOOKUP_FLAGS = MethodHandles.Lookup.PUBLIC | MethodHandles.Lookup.PACKAGE | MethodHandles.Lookup.PROTECTED | MethodHandles.Lookup.PRIVATE;
    //must lazy before thread pool init.
    static final Lazy<Cache<Class<?>, Map<String, Linq<Method>>>> methodCache = new Lazy<>(MemoryCache::new);
    static final Lazy<Cache<Class<?>, Map<String, Field>>> fieldCache = new Lazy<>(MemoryCache::new);
    static final Constructor<MethodHandles.Lookup> lookupConstructor;
    static final List<ConvertBean<?, ?>> convertBeans = new CopyOnWriteArrayList<>();

    static {
        try {
            lookupConstructor = MethodHandles.Lookup.class.getDeclaredConstructor(Class.class, int.class);
            setAccess(lookupConstructor);
        } catch (NoSuchMethodException e) {
            throw InvalidException.sneaky(e);
        }

        registerConvert(String.class, BigDecimal.class, (sv, tt) -> new BigDecimal(sv));
        registerConvert(String.class, UUID.class, (sv, tt) -> UUID.fromString(sv));
        registerConvert(String.class, Date.class, (sv, tt) -> DateTime.valueOf(sv));
        registerConvert(Number.class, Decimal.class, (sv, tt) -> Decimal.valueOf(sv.doubleValue()));
        registerConvert(Long.class, Date.class, (sv, tt) -> new Date(sv));
        registerConvert(Long.class, DateTime.class, (sv, tt) -> new DateTime(sv, TimeZone.getDefault()));
        registerConvert(Date.class, Long.class, (sv, tt) -> sv.getTime());
        registerConvert(Date.class, DateTime.class, (sv, tt) -> DateTime.of(sv));
        registerConvert(NEnum.class, Integer.class, (sv, tt) -> sv.getValue());
    }

    //region class
//    public static Class<?> getCallerClass() {
//        try {
//            Class<?> type = Class.forName("sun.reflect.Reflection");
//            return invokeMethod(type, "getCallerClass");
//        } catch (ClassNotFoundException e) {
//            return CLASS_TRACER.getClassTrace(0);
//        }
//    }

//    public static StackTraceElement[] getCallerStack() {
//        //Throwable.class.getDeclaredMethod("getStackTraceElement", int.class) & Reflection.getCallerClass(2 + depth) java 11 not exist
//        return new Throwable().getStackTrace();
//    }

    public static String getStackTrace(Thread t) {
        StringBuilder buf = new StringBuilder();
        StackTraceElement[] stackTrace = t.getStackTrace();
        for (int i = 1; i < stackTrace.length; i++) {
            buf.append("\tat ").appendLine(stackTrace[i]);
        }
        return buf.toString();
    }

    public static InputStream getResource(String namePattern) {
        InputStream in = getClassLoader().getResourceAsStream(namePattern);
        if (in != null) {
            return in;
        }
        in = getResources(namePattern).firstOrDefault();
        if (in == null) {
            throw new InvalidException("Resource {} not found", namePattern);
        }
        return in;
    }

    @SneakyThrows
    public static Linq<InputStream> getResources(String namePattern) {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        return Linq.from(resolver.getResources("classpath*:" + namePattern)).select(InputStreamSource::getInputStream);
    }

    //ClassLoader.getSystemClassLoader()
    public static ClassLoader getClassLoader() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return loader != null ? loader : Reflects.class.getClassLoader();
    }

    public static <T> Class<T> loadClass(String className, boolean initialize) {
        return loadClass(className, initialize, true);
    }

    //ClassPath.from(classloader).getTopLevelClasses(packageDirName)
    public static <T> Class<T> loadClass(String className, boolean initialize, boolean throwOnEmpty) {
        try {
            return (Class<T>) Class.forName(className, initialize, getClassLoader());
        } catch (ClassNotFoundException e) {
            if (!throwOnEmpty) {
                return null;
            }
            throw InvalidException.sneaky(e);
        }
    }

    public static boolean isInstance(Object val, Type type) {
        return TypeUtils.isInstance(val, type);
    }
    //endregion

    public static <TP, TR> String resolveProperty(BiFunc<TP, TR> func) {
        SerializedLambda lambda = getLambda(func);
        return propertyName(lambda.getImplMethodName());
    }

    public static <TP, TR> Tuple<String, String> resolveImpl(BiFunc<TP, TR> func) {
        SerializedLambda lambda = getLambda(func);
        String declaredClass = lambda.getImplClass().replace("/", ".");
        return Tuple.of(declaredClass, propertyName(lambda.getImplMethodName()));
    }

    @SneakyThrows
    public static <TP, TR> Field resolve(BiFunc<TP, TR> func) {
        SerializedLambda lambda = getLambda(func);
        String declaredClass = lambda.getImplClass().replace("/", ".");
        return getFieldMap(Class.forName(declaredClass)).get(propertyName(lambda.getImplMethodName()));
    }

    static <TP, TR> SerializedLambda getLambda(BiFunc<TP, TR> func) {
        SerializedLambda lambda = invokeMethod(func, "writeReplace");
        String implMethodName = lambda.getImplMethodName();
        if (implMethodName.startsWith("lambda$")) {
            throw new IllegalArgumentException("BiFunc can not be LAMBDA EXPR, but only METHOD REFERENCE");
        }
        if (!implMethodName.startsWith(GET_PROPERTY) && !implMethodName.startsWith(GET_BOOL_PROPERTY)) {
            throw new IllegalArgumentException(implMethodName + " is not a GETTER");
        }
        return lambda;
    }

    static final byte APPEND_TO_COLLECTION = 1;
    static final byte WRITE_QUIETLY = 1 << 1;

    /**
     * @param instance
     * @param fieldPath
     * @param value
     * @param flags     1       append to Collection
     *                  1 << 1  write quietly
     */
    public static void writeFieldByPath(Object instance, String fieldPath, Object value, byte flags) {
        final String c = ".";
        int wPathOffset = 0, i;
        String fieldName;
        Object cur = instance;
        while ((i = fieldPath.indexOf(c, wPathOffset)) != -1) {
            fieldName = fieldPath.substring(wPathOffset, i);
            try {
                cur = readField(cur, fieldName);
            } catch (Throwable e) {
                throw new InvalidException("Read field {} fail", fieldPath.substring(0, wPathOffset), e);
            }
            wPathOffset = i + c.length();
        }
        if (cur == null) {
            throw new InvalidException("Read field {} empty", fieldPath.substring(0, wPathOffset));
        }
        fieldName = fieldPath.substring(wPathOffset);
        try {
            Field field = getFieldMap(cur.getClass()).get(fieldName);
            if (field == null) {
                throw new NoSuchFieldException(fieldName);
            }

            if (isTypedJson(value)) {
                value = fromTypedJson(value);
            }
            if (Collection.class.isAssignableFrom(field.getType())) {
                Collection<Object> col = (Collection<Object>) field.get(cur);
                if ((flags & APPEND_TO_COLLECTION) != APPEND_TO_COLLECTION) {
                    col.clear();
                }
                if (value != null) {
                    col.addAll((Collection<Object>) value);
                }
            } else if (Map.class.isAssignableFrom(field.getType())) {
                Map<Object, Object> col = (Map<Object, Object>) field.get(cur);
                if ((flags & APPEND_TO_COLLECTION) != APPEND_TO_COLLECTION) {
                    col.clear();
                }
                if (value != null) {
                    col.putAll((Map<Object, Object>) value);
                }
            } else {
                field.set(cur, changeType(value, field.getType()));
            }
        } catch (Throwable e) {
            if ((flags & WRITE_QUIETLY) != WRITE_QUIETLY) {
                throw new InvalidException("Write field {} fail", fieldPath, e);
            }
            log.warn("Write field {} fail", fieldPath, e);
        }
    }

    public static boolean isTypedJson(Object value) {
        if (value instanceof String) {
            String str = ((String) value).trim();
            return str.startsWith("{") && str.endsWith("}") && str.indexOf(Constants.TYPED_JSON_KEY, 1) != -1;
        }
        Map<String, Object> json = as(value, Map.class);
        if (json != null) {
            return json.containsKey(Constants.TYPED_JSON_KEY);
        }
        return false;
    }

    public static <T> T fromTypedJson(Object value) {
        Map<String, Object> json = as(value, Map.class);
        if (json == null) {
            json = toJsonObject(value);
        }
        if (json == null || !json.containsKey(Constants.TYPED_JSON_KEY)) {
            throw new InvalidException("Invalid json");
        }
        return fromTypedJson(json);
    }

    @SneakyThrows
    public static <T> T fromTypedJson(@NonNull Map<String, Object> json) {
        String td = (String) json.get(Constants.TYPED_JSON_KEY);
        if (td == null) {
            String baseValue = (String) json.get("baseValue");
            if (baseValue == null) {
                throw new InvalidException("Invalid type descriptor");
            }
            return (T) JSON.parseObject(baseValue, Class.forName(json.get("baseType").toString()));
        }
        return fromJson(json, fromTypeDescriptor(td));
    }

    public static <T> JSONObject toTypedJson(@NonNull T obj) {
        return toTypedJson(obj, obj.getClass());
    }

    public static <T> JSONObject toTypedJson(@NonNull T obj, @NonNull Type type) {
        try {
            JSONObject r = toJsonObject(obj);
            r.put(Constants.TYPED_JSON_KEY, getTypeDescriptor(type));
            return r;
        } catch (Exception e) {
            if (Strings.startsWith(e.getMessage(), "Invalid json")) {
                JSONObject r = new JSONObject(1);
                r.put("baseValue", JSON.toJSONString(obj));
                r.put("baseType", obj.getClass().getName());
                return r;
            }
            throw e;
        }
    }

    public static String getTypeDescriptor(@NonNull Type type) {
        if (type instanceof Class) {
            return ((Class<?>) type).getName();
        }
        return JSON.toJSONString(type);
    }

    @SneakyThrows
    public static Type fromTypeDescriptor(@NonNull String descriptor) {
        if (descriptor.startsWith("{")) {
            Map<String, Object> typeJson = fromJson(descriptor, Map.class);
            return fromParameterizedType(typeJson);
        }
        return ClassUtils.getClass(descriptor);
    }

    @SneakyThrows
    static ParameterizedType fromParameterizedType(Map<String, Object> typeJson) {
        String ownerType = (String) typeJson.get("ownerType");
        String rawType = (String) typeJson.get("rawType");
        List<Object> actualTypeArguments = (List<Object>) typeJson.get("actualTypeArguments");
        return TypeUtils.parameterizeWithOwner((ownerType == null ? null : ClassUtils.getClass(ownerType)), ClassUtils.getClass(rawType), fromTypeArguments(actualTypeArguments));
    }

    static Type[] fromTypeArguments(List<Object> typeArguments) {
        return Linq.from(typeArguments).select(p -> {
            if (p instanceof Map) {
                Map<String, Object> typeArg = (Map<String, Object>) p;
                List<Object> lowerBounds = (List<Object>) typeArg.get("lowerBounds");
                List<Object> upperBounds = (List<Object>) typeArg.get("upperBounds");
                if (lowerBounds != null && upperBounds != null) {
                    return TypeUtils.wildcardType()
                            .withLowerBounds(fromTypeArguments(lowerBounds))
                            .withUpperBounds(fromTypeArguments(upperBounds)).build();
                }
                return fromParameterizedType(typeArg);
            }
            return ClassUtils.getClass((String) p);
        }).toArray(Type.class);
    }

    //region methods
    public static <T> T newInstance(Class<?> type) {
        return newInstance(type, Arrays.EMPTY_OBJECT_ARRAY);
    }

    @ErrorCode
    @SneakyThrows
    public static <T> T newInstance(Class<?> type, Object... args) {
        if (args == null) {
            args = Arrays.EMPTY_OBJECT_ARRAY;
        }

        try {
            return (T) ConstructorUtils.invokeConstructor(type, args);
        } catch (Exception e) {
            log.warn("Not match any accessible constructors. {}", e.getMessage());
            Constructor<?> ctor = findMatchingExecutable(type, null, args);
            if (ctor != null) {
                setAccess(ctor);
                return (T) ctor.newInstance(args);
            }
        }
        throw new ApplicationException(values(type.getName()));
    }

    public static <T extends Executable> T findMatchingExecutable(Class<?> type, String name, Object[] args) {
        Executable executable = null;
        if (name != null) {
            Linq<Method> methods = getMethodMap(type).get(name);
            if (methods != null) {
                for (Executable p : methods) {
                    if (match(p, args)) {
                        executable = p;
                        break;
                    }
                }
            }
        } else {
            for (Constructor<?> p : type.getDeclaredConstructors()) {
                if (match(p, args)) {
                    executable = p;
                    break;
                }
            }
        }
        return (T) executable;
    }

    static boolean match(Executable p, Object[] args) {
        if (p.getParameterCount() != args.length) {
            return false;
        }
        Class<?>[] parameterTypes = p.getParameterTypes();
        for (int i = 0; i < parameterTypes.length; i++) {
            Class<?> parameterType = parameterTypes[i];
            Object arg = args[i];
            if (arg == null) {
                if (parameterType.isPrimitive()) {
                    return false;
                }
                continue;
            }
            if (!primitiveToWrapper(parameterType).isInstance(arg)) {
                return false;
            }
//            if (!TypeUtils.isInstance(arg, parameterType)) {
//                return false;
//            }
        }
        return true;
    }

    @SneakyThrows
    public static <T> T invokeDefaultMethod(Method method, Object instance, Object... args) {
        require(method, method.isDefault());

        Class<?> declaringClass = method.getDeclaringClass();
        MethodHandle methodHandle;
//        if (Sys.IS_JAVA_11) {
//            methodHandle = MethodHandles.lookup()
//                    .findSpecial(
//                            method.getDeclaringClass(),
//                            method.getName(),
//                            MethodType.methodType(method.getReturnType(), Arrays.EMPTY_CLASS_ARRAY),
//                            method.getDeclaringClass()
//                    );
//        } else {
        methodHandle = lookupConstructor.newInstance(declaringClass, LOOKUP_FLAGS)
                .unreflectSpecial(method, declaringClass);
//        }
        return (T) methodHandle.bindTo(instance)
                .invokeWithArguments(args);
    }

    public static boolean invokeCloseMethod(Method method, Object instance) {
        if (!isCloseMethod(method)) {
            return false;
        }
        return tryClose(instance);
    }

    public static boolean isCloseMethod(Method method) {
        return Strings.hashEquals(method.getName(), M_0) && method.getParameterCount() == 0;
    }

    public static <T, TT> T invokeStaticMethod(Class<? extends TT> type, String name, Object... args) {
        return invokeMethod(type, null, name, args);
    }

    public static <T, TT> T invokeMethod(TT instance, String name, Object... args) {
        return invokeMethod(null, instance, name, args);
    }

    @SneakyThrows
    @ErrorCode
    public static <T, TT> T invokeMethod(Class<? extends TT> type, TT instance, String name, Object... args) {
        boolean isStatic = type != null;
        Class<?> searchType = isStatic ? type : instance.getClass();
        Method method = findMatchingExecutable(searchType, name, args);
        if (method != null) {
            return (T) method.invoke(instance, args);
        }

        try {
            if (isStatic) {
                Class<?>[] parameterTypes = toClass(args);  //May not right match if args have null value
                method = MethodUtils.getMatchingMethod(searchType, name, parameterTypes);
                return invokeMethod(method, args);
            } else {
                return (T) MethodUtils.invokeMethod(instance, true, name, args);
            }
        } catch (Exception e) {
            throw new ApplicationException(values(searchType.getName(), name), e);
        }
    }

    @SneakyThrows
    public static <T, TT> T invokeMethod(Method method, TT instance, Object... args) {
        setAccess(method);
        return (T) method.invoke(instance, args);
    }

    public static Method getInterfaceMethod(Method method) {
        Cache<Method, Object> cache = Cache.getInstance(MemoryCache.class);
        Object v = cache.get(method, k -> {
            Class<?> type = method.getDeclaringClass();
            NoSuchMethodException lastEx = null;
            for (Class<?> i : type.getInterfaces()) {
                try {
                    return i.getMethod(method.getName(), method.getParameterTypes());
                } catch (NoSuchMethodException e) {
                    lastEx = e;
                }
            }
            if (lastEx != null) {
                log.warn("getInterfaceMethod", lastEx);
            }
            return Cache.NULL_VALUE;
        });
        if (v == Cache.NULL_VALUE) {
            return null;
        }
        return (Method) v;
    }

    public static Map<String, Linq<Method>> getMethodMap(Class<?> type) {
        return methodCache.getValue().get(type, k -> {
            Set<Method> all = new HashSet<>();
            for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                Method[] declared = type.getDeclaredMethods(); //can't get kotlin private methods
                for (Method method : declared) {
                    setAccess(method);
                }
                Collections.addAll(all, declared);
            }

            Linq<Method> defMethods = Linq.from(type.getInterfaces()).selectMany(p -> Linq.from(p.getMethods())).where(p -> {
                boolean d = p.isDefault();
                if (d) {
                    setAccess(p);
                }
                return d;
            });
            all.addAll(defMethods.toList());
            return Collections.unmodifiableMap(Linq.from(all).groupByIntoMap(Method::getName, (p, x) -> x));
        });
    }
    //endregion

    //region fields
    public static Linq<PropertyNode> getProperties(Class<?> to) {
        Cache<String, Linq<PropertyNode>> cache = Cache.getInstance(MemoryCache.class);
        return cache.get(fastCacheKey(Constants.CACHE_REGION_BEAN_PROPERTIES, to), k -> {
            Method getClass = Object.class.getDeclaredMethod("getClass");
            Linq<Method> q = Linq.from(to.getMethods());
            Linq<Tuple<String, Method>> setters = q.where(p -> p.getParameterCount() == 1 && p.getName().startsWith(SET_PROPERTY)).select(p -> Tuple.of(propertyName(p.getName()), p));
            Linq<Tuple<String, Method>> getters = q.where(p -> p.getParameterCount() == 0 && p != getClass && (p.getName().startsWith(GET_PROPERTY) || p.getName().startsWith(GET_BOOL_PROPERTY))).select(p -> Tuple.of(propertyName(p.getName()), p));
            return setters.join(getters.toList(), (p, x) -> Strings.hashEquals(p.left, x.left), (p, x) -> new PropertyNode(p.left, p.right, x.right));
        });
    }

    public static String propertyName(String getterOrSetter) {
        String name;
        if (getterOrSetter.startsWith(GET_PROPERTY)) {
            name = getterOrSetter.substring(GET_PROPERTY.length());
        } else if (getterOrSetter.startsWith(GET_BOOL_PROPERTY)) {
            name = getterOrSetter.substring(GET_BOOL_PROPERTY.length());
        } else if (getterOrSetter.startsWith(SET_PROPERTY)) {
            name = getterOrSetter.substring(SET_PROPERTY.length());
        } else {
            name = getterOrSetter;
        }
        if (name.isEmpty()) {
            throw new InvalidException("Invalid name {}", getterOrSetter);
        }

        if (Character.isLowerCase(name.charAt(0))) {
            return name;
        }
        return name.substring(0, 1).toLowerCase() + name.substring(1);
    }

    @SneakyThrows
    public static <T> void copyPublicFields(T from, T to) {
        for (Field field : getFieldMap(to.getClass()).values()) {
            if (!Modifier.isPublic(field.getModifiers())) {
                continue;
            }
            field.set(to, field.get(from));
        }
    }

    public static <T, TT> T readStaticField(Class<? extends TT> type, String name) {
        return readField(type, null, name);
    }

    public static <T, TT> T readField(TT instance, String name) {
        return readField(instance.getClass(), instance, name);
    }

    @SneakyThrows
    public static <T, TT> T readField(Class<? extends TT> type, TT instance, String name) {
        Field field = getFieldMap(type).get(name);
        if (field == null) {
            throw new NoSuchFieldException(name);
        }
        return (T) field.get(instance);
    }

    public static <T, TT> void writeStaticField(Class<? extends TT> type, String name, T value) {
        writeField(type, null, name, value);
    }

    public static <T, TT> void writeField(TT instance, String name, T value) {
        writeField(instance.getClass(), instance, name, value);
    }

    @SneakyThrows
    public static <T, TT> void writeField(Class<? extends TT> type, TT instance, String name, T value) {
        Field field = getFieldMap(type).get(name);
        if (field == null) {
            throw new NoSuchFieldException(name);
        }
        field.set(instance, changeType(value, field.getType()));
    }

    public static Map<String, Field> getFieldMap(Class<?> type) {
        return fieldCache.getValue().get(type, k -> {
            List<Field> all = FieldUtils.getAllFieldsList(type);
            for (Field field : all) {
                setAccess(field);
                FieldUtils.removeFinalModifier(field);
            }
            return Collections.unmodifiableMap(Linq.from(all).toMap(Field::getName, p -> p));
        });
    }

    public static void setAccess(AccessibleObject member) {
        if (member.isAccessible()) {
            return;
        }
        try {
            if (member instanceof Field) {
                Field field = (Field) member;
                FieldUtils.removeFinalModifier(field);
            }

//            member.setAccessible(true);
            if (System.getSecurityManager() == null) {
                member.setAccessible(true); // <~ Dragons
            } else {
                AccessController.doPrivileged((PrivilegedAction<Object>) () -> {
                    member.setAccessible(true);  // <~ moar Dragons
                    return null;
                });
            }
        } catch (Exception e) {
            log.warn("setAccess", e);
        }
    }

    public static <T> T convertQuietly(Object val, Class<T> toType) {
        return convertQuietly(val, toType, null);
    }

    public static <T> T convertQuietly(Object val, @NonNull Class<T> toType, T defaultVal) {
        try {
            return ifNull(changeType(val, toType), defaultVal);
        } catch (Throwable e) {
            return defaultVal;
        }
    }

    public static <TS, TT> void registerConvert(@NonNull Class<TS> baseFromType, @NonNull Class<TT> toType, @NonNull TripleFunc<TS, Class<TT>, TT> converter) {
        convertBeans.add(0, new ConvertBean<>(baseFromType, toType, converter));
    }

    public static <T> T defaultValue(@NonNull Class<T> type) {
        return changeType(null, type);
    }

    @SuppressWarnings(NON_RAW_TYPES)
    @ErrorCode("enumError")
    @ErrorCode(cause = NoSuchMethodException.class)
    @ErrorCode(cause = ReflectiveOperationException.class)
    public static <T> T changeType(Object value, Class<T> toType) {
        if (value == null) {
            if (!toType.isPrimitive()) {
                if (toType == List.class) {
                    return (T) Collections.emptyList();
                }
                if (toType == Map.class) {
                    return (T) Collections.emptyMap();
                }
                return null;
            }
            if (toType == boolean.class) {
                return (T) Boolean.FALSE;
            } else {
                value = 0;
            }
        }

        Object fValue = value;
        if (toType == String.class) {
            value = value.toString();
        } else if (toType.isEnum()) {
            boolean failBack = true;
            if (NEnum.class.isAssignableFrom(toType)) {
                if (value instanceof String) {
                    try {
                        value = Integer.valueOf((String) value);
                    } catch (NumberFormatException e) {
                        //ignore
                    }
                }
                if (value instanceof Number) {
                    int val = ((Number) value).intValue();
                    value = NEnum.valueOf((Class) toType, val);
                    failBack = false;
                }
            }
            if (failBack) {
                String val = value.toString();
                value = Linq.from(toType.getEnumConstants()).singleOrDefault(p -> ((Enum) p).name().equals(val));
            }
            if (value == null) {
                throw new ApplicationException("enumError", values(fValue, toType.getSimpleName()));
            }
        } else if (!toType.isPrimitive() && TypeUtils.isInstance(value, toType)) {
            //int to long | int to Object ok, DO NOTHING
            //long to int not ok
        } else {
            Class<?> fromType = value.getClass();
            try {
                toType = (Class) primitiveToWrapper(toType);
                if (toType == Boolean.class && value instanceof Number) {
                    byte val = ((Number) value).byteValue();
                    if (val == 0) {
                        value = Boolean.FALSE;
                    } else if (val == 1) {
                        value = Boolean.TRUE;
                    } else {
                        throw new InvalidException("Value should be 0 or 1");
                    }
                } else if (toType == Class.class && value instanceof String) {
                    //todo check
                    value = loadClass(value.toString(), true);
                } else {
                    Linq<Method> methods = getMethodMap(toType).get(CHANGE_TYPE_METHOD);
                    if (methods == null || fromType.isEnum()) {
                        Class<T> fType = toType;
                        ConvertBean convertBean = Linq.from(convertBeans).firstOrDefault(p -> TypeUtils.isInstance(fValue, p.baseFromType) && p.toType.isAssignableFrom(fType));
                        if (convertBean != null) {
                            return (T) convertBean.converter.apply(value, convertBean.toType);
                        }
                        throw new NoSuchMethodException(CHANGE_TYPE_METHOD);
                    }

                    if (Number.class.isAssignableFrom(toType)) {
                        if (value instanceof Boolean) {
                            if (!(Boolean) value) {
                                value = "0";
                            } else {
                                value = "1";
                            }
                        } else if (value instanceof Number) {
                            //BigDecimal 1.001 to 1
                            Number num = (Number) value;
                            if (toType == Integer.class) {
                                value = num.intValue();
                            } else if (toType == Long.class) {
                                value = num.longValue();
                            } else if (toType == Byte.class) {
                                value = num.byteValue();
                            } else if (toType == Short.class) {
                                value = num.shortValue();
                            }
                        }
                    }

                    Method m = null;
                    for (Method p : methods) {
                        if (!(p.getParameterCount() == 1 && p.getParameterTypes()[0] == String.class)) {
                            continue;
                        }
                        m = p;
                        break;
                    }
                    if (m == null) {
                        m = toType.getDeclaredMethod(CHANGE_TYPE_METHOD, String.class);
                    }
                    value = m.invoke(null, value.toString());
                }
            } catch (NoSuchMethodException e) {
                throw new ApplicationException(values(toType), e);
            } catch (ReflectiveOperationException e) {
                throw new ApplicationException(values(fromType, toType, value), e);
            }
        }
        return (T) value;
    }

    public static boolean isBasicType(@NonNull Class<?> type) {
        return type.isPrimitive() || type == String.class || Number.class.isAssignableFrom(type) || type == Boolean.class
                || type.isEnum()
                || Date.class.isAssignableFrom(type)
                || type == ULID.class
                || type == Class.class
                || type == UUID.class;
//        Class<?> wrapType;
//        return type == String.class || Number.class.isAssignableFrom(wrapType = primitiveToWrapper(type))
//                || wrapType == Boolean.class
//                || type.isEnum()
//                || Date.class.isAssignableFrom(type)
//                || type == ULID.class
//                || type == Class.class
//                || type == UUID.class;
    }
    //endregion

    @SneakyThrows
    public static void trimToNull(Object dto) {
        if (dto == null) {
            return;
        }
        for (Field field : Linq.from(getFieldMap(dto.getClass()).values()).where(p -> p.getType() == String.class)) {
            String v = (String) field.get(dto);
            if (Strings.isEmpty(v)) {
                field.set(dto, null);
            }
        }
    }
}
