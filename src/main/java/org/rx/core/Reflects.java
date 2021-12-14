package org.rx.core;

import lombok.Getter;
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
import org.rx.exception.ApplicationException;
import org.rx.exception.InvalidException;
import org.rx.util.function.BiFunc;
import org.springframework.core.io.InputStreamSource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.InputStream;
import java.io.Serializable;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;

import static org.rx.core.App.*;
import static org.rx.core.Constants.NON_RAW_TYPES;
import static org.rx.core.Constants.NON_UNCHECKED;

@Slf4j
public class Reflects extends TypeUtils {
    //region NestedTypes
    @RequiredArgsConstructor
    public static class PropertyNode implements Serializable {
        private static final long serialVersionUID = 3680733077204898075L;
        public final String propertyName;
        public final Method setter;
        public final Method getter;
    }

    @RequiredArgsConstructor
    @Getter
    private static class ConvertBean<TS, TT> {
        private final Class<TS> baseFromType;
        private final Class<TT> toType;
        private final BiFunction<TS, Class<TT>, TT> converter;
    }

    static class SecurityManagerEx extends SecurityManager {
        static final SecurityManagerEx INSTANCE = new SecurityManagerEx();

        Class<?> stackClass(int depth) {
            return getClassContext()[depth];
        }
    }
    //endregion

    public static final NQuery<String> COLLECTION_WRITE_METHOD_NAMES = NQuery.of("add", "remove", "addAll", "removeAll", "removeIf", "retainAll", "clear"),
            List_WRITE_METHOD_NAMES = COLLECTION_WRITE_METHOD_NAMES.union(Arrays.toList("replaceAll", "set"));
    public static final NQuery<Method> OBJECT_METHODS = NQuery.of(Object.class.getMethods());
    static final int CLOSE_METHOD_HASH = "close".hashCode();
    private static final String getProperty = "get", getBoolProperty = "is", setProperty = "set";
    private static final Constructor<MethodHandles.Lookup> lookupConstructor;
    private static final int lookupFlags = MethodHandles.Lookup.PUBLIC | MethodHandles.Lookup.PROTECTED | MethodHandles.Lookup.PRIVATE | MethodHandles.Lookup.PACKAGE;
    private static final List<ConvertBean<?, ?>> typeConverter;

    static {
        try {
            lookupConstructor = MethodHandles.Lookup.class.getDeclaredConstructor(Class.class, int.class);
            setAccess(lookupConstructor);
        } catch (NoSuchMethodException e) {
            throw InvalidException.sneaky(e);
        }

        typeConverter = new CopyOnWriteArrayList<>();
        registerConvert(Number.class, Decimal.class, (sv, tt) -> Decimal.valueOf(sv.doubleValue()));
        registerConvert(NEnum.class, Integer.class, (sv, tt) -> sv.getValue());
        registerConvert(Date.class, DateTime.class, (sv, tt) -> new DateTime(sv));
        registerConvert(String.class, SUID.class, (sv, tt) -> SUID.valueOf(sv));
    }

    //region class
    public static void dumpStack(StringBuilder msg) {
        for (StackTraceElement stack : stackTrace(12)) {
            msg.appendLine("%s.%s(%s:%s)", stack.getClassName(), stack.getMethodName(), stack.getFileName(), stack.getLineNumber());
        }
    }

    public static NQuery<StackTraceElement> stackTrace(int takeCount) {
        //Thread.currentThread().getStackTrace()性能略差
        return NQuery.of(new Throwable().getStackTrace()).skip(2).take(takeCount);
    }

    public static Class<?> stackClass(int depth) {
        //Throwable.class.getDeclaredMethod("getStackTraceElement", int.class) & Reflection.getCallerClass(2 + depth) java 11 获取不到
        return SecurityManagerEx.INSTANCE.stackClass(2 + depth);
    }

    public static InputStream getResource(String namePattern) {
        InputStream stream = getClassLoader().getResourceAsStream(namePattern);
        if (stream != null) {
            return stream;
        }
        InputStream in = getResources(namePattern).firstOrDefault();
        if (in == null) {
            throw new InvalidException("Resource %s not found", namePattern);
        }
        return in;
    }

    //class.getResourceAsStream
    @SneakyThrows
    public static NQuery<InputStream> getResources(String namePattern) {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        return NQuery.of(resolver.getResources("classpath*:" + namePattern)).select(InputStreamSource::getInputStream);
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
    @SuppressWarnings(NON_UNCHECKED)
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

    public static <T> T newInstance(Class<T> type) {
        return newInstance(type, Arrays.EMPTY_OBJECT_ARRAY);
    }

    @SuppressWarnings(NON_UNCHECKED)
    @ErrorCode
    @SneakyThrows
    public static <T> T newInstance(@NonNull Class<T> type, Object... args) {
        if (args == null) {
            args = Arrays.EMPTY_OBJECT_ARRAY;
        }

        try {
            return ConstructorUtils.invokeConstructor(type, args);
        } catch (Exception e) {
            log.warn("Not match any accessible constructors. {}", e.getMessage());
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                Class<?>[] paramTypes = constructor.getParameterTypes();
                if (paramTypes.length != args.length) {
                    continue;
                }
                boolean ok = true;
                for (int i = 0; i < paramTypes.length; i++) {
                    if (!isInstance(args[i], paramTypes[i])) {
                        ok = false;
                        break;
                    }
                }
                if (!ok) {
                    continue;
                }
                setAccess(constructor);
                return (T) constructor.newInstance(args);
            }
        }
        throw new ApplicationException(values(type.getName()));
    }
    //endregion

    public static <TP, TR> Tuple<String, String> resolve(BiFunc<TP, TR> func) {
        SerializedLambda serializedLambda = invokeMethod(func, "writeReplace");
        String implMethodName = serializedLambda.getImplMethodName();
        if (implMethodName.startsWith("lambda$")) {
            throw new IllegalArgumentException("BiFunc can not be LAMBDA EXPR, but only METHOD REFERENCE");
        }
        String fieldName;
        if ((implMethodName.startsWith("get") && implMethodName.length() > 3)
                || implMethodName.startsWith("is") && implMethodName.length() > 2) {
            fieldName = propertyName(implMethodName);
        } else {
            throw new IllegalArgumentException(implMethodName + " is not a GETTER");
        }
        String declaredClass = serializedLambda.getImplClass().replace("/", ".");
        return Tuple.of(declaredClass, fieldName);
    }

    @SuppressWarnings(NON_UNCHECKED)
    @SneakyThrows
    public static <T> T invokeDefaultMethod(@NonNull Method method, Object instance, Object... args) {
        require(method, method.isDefault());

        Class<?> declaringClass = method.getDeclaringClass();
        return (T) lookupConstructor.newInstance(declaringClass, lookupFlags)
                .unreflectSpecial(method, declaringClass)
                .bindTo(instance)
                .invokeWithArguments(args);
    }

    public static boolean invokeCloseMethod(Method method, Object instance) {
        if (!isCloseMethod(method)) {
            return false;
        }
        return tryClose(instance);
    }

    public static boolean isCloseMethod(Method method) {
        //String hashcode has cached
        return method.getName().hashCode() == CLOSE_METHOD_HASH && method.getParameterCount() == 0;
    }

    public static <T, TT> T invokeStaticMethod(Class<? extends TT> type, String name, Object... args) {
        return invokeMethod(type, null, name, args);
    }

    public static <T, TT> T invokeMethod(TT instance, String name, Object... args) {
        return invokeMethod(null, instance, name, args);
    }

    @SuppressWarnings(NON_UNCHECKED)
    @SneakyThrows
    @ErrorCode
    public static <T, TT> T invokeMethod(Class<? extends TT> type, TT instance, String name, Object... args) {
        boolean isStatic = type != null;
        Class<?> searchType = isStatic ? type : instance.getClass();
        Method method = null;
        NQuery<Method> methods = getMethodMap(searchType).get(name);
        if (methods != null) {
            method = methods.firstOrDefault(p -> {
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
                    if (!ClassUtils.primitiveToWrapper(parameterType).isInstance(arg)) {
                        return false;
                    }
                }
                return true;
            });
        }
//        Method method = MethodUtils.getMatchingAccessibleMethod(type, name, parameterTypes);
        if (method == null) {
            try {
                if (isStatic) {
                    Class<?>[] parameterTypes = ClassUtils.toClass(args);  //null 不准
                    method = MethodUtils.getMatchingMethod(searchType, name, parameterTypes);
                    return invokeMethod(method, args);
                } else {
                    return (T) MethodUtils.invokeMethod(instance, true, name, args);
                }
            } catch (NoSuchMethodException e) {
                //ignore
            }
            throw new ApplicationException(values(searchType.getName(), name));
        }
        return (T) method.invoke(instance, args);
    }

    @SuppressWarnings(NON_UNCHECKED)
    @SneakyThrows
    public static <T, TT> T invokeMethod(Method method, TT instance, Object... args) {
        setAccess(method);
        return (T) method.invoke(instance, args);
    }

    public static Map<String, NQuery<Method>> getMethodMap(@NonNull Class<?> type) {
        return Cache.getOrSet(cacheKey("methodMap", type), k -> {
            List<Method> all = new ArrayList<>();
            for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                Method[] declared = type.getDeclaredMethods(); //can't get kotlin private methods
                for (Method method : declared) {
                    setAccess(method);
                }
                Collections.addAll(all, declared);
            }

            NQuery<Method> defMethods = NQuery.of(type.getInterfaces()).selectMany(p -> NQuery.of(p.getMethods())).where(p -> {
                boolean d = p.isDefault();
                if (d) {
                    setAccess(p);
                }
                return d;
            });
            all.addAll(defMethods.toList());
            return Collections.unmodifiableMap(NQuery.of(all).groupByIntoMap(Method::getName, (p, x) -> x));
        }, Cache.MEMORY_CACHE);
    }

    //region fields
    public static NQuery<PropertyNode> getProperties(Class<?> to) {
        return Cache.getOrSet(cacheKey("properties", to), k -> {
            Method getClass = OBJECT_METHODS.first(p -> p.getName().equals("getClass"));
            NQuery<Method> q = NQuery.of(to.getMethods());
            NQuery<Tuple<String, Method>> setters = q.where(p -> p.getName().startsWith(setProperty) && p.getParameterCount() == 1).select(p -> Tuple.of(propertyName(p.getName()), p));
            NQuery<Tuple<String, Method>> getters = q.where(p -> p != getClass && (p.getName().startsWith(getProperty) || p.getName().startsWith(getBoolProperty)) && p.getParameterCount() == 0).select(p -> Tuple.of(propertyName(p.getName()), p));
            return setters.join(getters.toList(), (p, x) -> p.left.equals(x.left), (p, x) -> new PropertyNode(p.left, p.right, x.right));
        }, Cache.MEMORY_CACHE);
    }

    public static String propertyName(@NonNull String getterOrSetterName) {
        return Cache.getOrSet(cacheKey("propertyName", getterOrSetterName), k -> {
            String name;
            if (getterOrSetterName.startsWith(getProperty)) {
                name = getterOrSetterName.substring(getProperty.length());
            } else if (getterOrSetterName.startsWith(getBoolProperty)) {
                name = getterOrSetterName.substring(getBoolProperty.length());
            } else if (getterOrSetterName.startsWith(setProperty)) {
                name = getterOrSetterName.substring(setProperty.length());
            } else {
                name = getterOrSetterName;
            }

            //Introspector.decapitalize
            if (Character.isLowerCase(name.charAt(0))) {
                return name;
            }
            return name.substring(0, 1).toLowerCase() + name.substring(1);
        }, Cache.MEMORY_CACHE);
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

    public static <T, TT> T readField(TT instance, String name) {
        return readField(instance.getClass(), instance, name);
    }

    @SuppressWarnings(NON_UNCHECKED)
    @SneakyThrows
    public static <T, TT> T readField(Class<? extends TT> type, TT instance, String name) {
        Field field = getFieldMap(type).get(name);
        if (field == null) {
            throw new NoSuchFieldException(name);
        }
        return (T) field.get(instance);
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

    public static Map<String, Field> getFieldMap(@NonNull Class<?> type) {
        return Cache.getOrSet(cacheKey("fieldMap", type), k -> {
            List<Field> all = FieldUtils.getAllFieldsList(type);
            for (Field field : all) {
                setAccess(field);
            }
            return Collections.unmodifiableMap(NQuery.of(all).toMap(Field::getName, p -> p));
        }, Cache.MEMORY_CACHE);
    }

    public static void setAccess(AccessibleObject member) {
        if (member.isAccessible()) {
            return;
        }
        try {
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

    public static <T> T tryConvert(Object val, Class<T> toType) {
        return tryConvert(val, toType, null);
    }

    public static <T> T tryConvert(Object val, @NonNull Class<T> toType, T defaultVal) {
        try {
            return isNull(Reflects.changeType(val, toType), defaultVal);
        } catch (Exception ex) {
            return defaultVal;
        }
    }

    public static <TS, TT> void registerConvert(@NonNull Class<TS> baseFromType, @NonNull Class<TT> toType, @NonNull BiFunction<TS, Class<TT>, TT> converter) {
        typeConverter.add(0, new ConvertBean<>(baseFromType, toType, converter));
    }

    public static Object defaultValue(Class<?> type) {
        return changeType(null, type);
    }

    @SuppressWarnings(NON_RAW_TYPES)
    @ErrorCode("enumError")
    @ErrorCode(cause = NoSuchMethodException.class)
    @ErrorCode(cause = ReflectiveOperationException.class)
    public static <T> T changeType(Object value, @NonNull Class<T> toType) {
        if (value == null) {
            if (!toType.isPrimitive()) {
                if (List.class.equals(toType)) {
                    return (T) Collections.emptyList();
                }
                if (Map.class.equals(toType)) {
                    return (T) Collections.emptyMap();
                }
                return null;
            }
            if (boolean.class.equals(toType)) {
                return (T) Boolean.FALSE;
            } else {
                value = 0;
            }
        }

        Class<?> fromType = value.getClass();
        Object fValue = value;
        if (toType.equals(String.class)) {
            value = value.toString();
        } else if (toType.equals(UUID.class)) {
            value = UUID.fromString(value.toString());
        } else if (toType.equals(BigDecimal.class)) {
            value = new BigDecimal(value.toString());
        } else if (toType.isEnum()) {
            if (NEnum.class.isAssignableFrom(toType) && ClassUtils.isAssignable(fromType, Number.class)) {
                int val = ((Number) value).intValue();
                value = NEnum.valueOf((Class) toType, val);
            } else {
                String val = value.toString();
                value = NQuery.of(toType.getEnumConstants()).singleOrDefault(p -> ((Enum) p).name().equals(val));
            }
            if (value == null) {
                throw new ApplicationException("enumError", values(fValue, toType.getSimpleName()));
            }
        } else if (!toType.isPrimitive() && Reflects.isInstance(value, toType)) {
            //isInstance int to long ok, do nothing
        } else {
            try {
                toType = (Class) ClassUtils.primitiveToWrapper(toType);
                if (toType.equals(Boolean.class) && ClassUtils.isAssignable(fromType, Number.class)) {
                    int val = ((Number) value).intValue();
                    if (val == 0) {
                        value = Boolean.FALSE;
                    } else if (val == 1) {
                        value = Boolean.TRUE;
                    } else {
                        throw new InvalidException("Value should be 0 or 1");
                    }
                } else {
                    NQuery<Method> valueOf = getMethodMap(toType).get("valueOf");
                    if (valueOf == null) {
                        Class<T> fType = toType;
                        ConvertBean convertBean = NQuery.of(typeConverter).firstOrDefault(p -> Reflects.isInstance(fValue, p.getBaseFromType()) && p.getToType().isAssignableFrom(fType));
                        if (convertBean != null) {
                            return (T) convertBean.getConverter().apply(value, convertBean.getToType());
                        }
                    }

                    if (ClassUtils.isAssignable(toType, Number.class) && ClassUtils.primitiveToWrapper(fromType).equals(Boolean.class)) {
                        boolean val = (boolean) value;
                        if (!val) {
                            value = "0";
                        } else {
                            value = "1";
                        }
                    }
                    value = invokeStaticMethod(toType, "valueOf", value.toString());
//                    Method m = toType.getDeclaredMethod("valueOf", String.class);
//                    value = m.invoke(null, value.toString());
                }
//            } catch (NoSuchMethodException ex) {
//                throw new ApplicationException(values(toType), ex);
//            } catch (ReflectiveOperationException ex) {
//                throw new ApplicationException(values(fromType, toType, value), ex);
//            }
            } catch (Exception e) {
                throw new ApplicationException(values(fromType, toType, value), e);
            }
        }
        return (T) value;
    }
    //endregion
}
