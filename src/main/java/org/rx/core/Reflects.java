package org.rx.core;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ClassUtils;
import org.apache.commons.lang3.reflect.ConstructorUtils;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.commons.lang3.reflect.MethodUtils;
import org.apache.commons.lang3.reflect.TypeUtils;
import org.rx.annotation.ErrorCode;
import org.rx.bean.DateTime;
import org.rx.bean.NEnum;
import org.rx.bean.SUID;
import org.rx.bean.Tuple;

import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;

import static org.rx.core.Contract.*;

@Slf4j
public class Reflects extends TypeUtils {
    //region NestedTypes
    @RequiredArgsConstructor
    public static class PropertyNode {
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
        static SecurityManagerEx instance = new SecurityManagerEx();

        Class callerClass(int depth) {
            return getClassContext()[depth];
        }
    }
    //endregion

    public static final NQuery<Method> OBJECT_METHODS = NQuery.of(Object.class.getMethods());
    private static final String getProperty = "get", getBoolProperty = "is", setProperty = "set", closeMethod = "close";
    private static final Constructor<MethodHandles.Lookup> lookupConstructor;
    private static final int lookupFlags = MethodHandles.Lookup.PUBLIC | MethodHandles.Lookup.PROTECTED | MethodHandles.Lookup.PRIVATE | MethodHandles.Lookup.PACKAGE;
    private static final List<Class<?>> supportTypes;
    private static final List<ConvertBean<?, ?>> typeConverter;

    static {
        try {
            lookupConstructor = MethodHandles.Lookup.class.getDeclaredConstructor(Class.class, int.class);
            setAccess(lookupConstructor);
        } catch (NoSuchMethodException e) {
            throw SystemException.wrap(e);
        }

        supportTypes = new CopyOnWriteArrayList<>(Arrays.toList(String.class, Boolean.class, Byte.class, Short.class, Integer.class, Long.class,
                Float.class, Double.class, Enum.class, Date.class, UUID.class, BigDecimal.class));
        typeConverter = new CopyOnWriteArrayList<>();
        registerConvert(NEnum.class, Integer.class, (sv, tt) -> sv.getValue());
//        registerConvert(Integer.class, NEnum.class, (sv, tt) -> Reflects.invokeMethod(NEnum.class, null, "valueOf", tt, sv));
        registerConvert(Date.class, DateTime.class, (sv, tt) -> new DateTime(sv));
        registerConvert(String.class, SUID.class, (sv, tt) -> SUID.valueOf(sv));
    }

    //region class
    public static void dumpStack(StringBuilder msg) {
        for (StackTraceElement stack : threadStack(12)) {
            msg.appendLine("%s.%s(%s:%s)", stack.getClassName(), stack.getMethodName(), stack.getFileName(), stack.getLineNumber());
        }
    }

    public static NQuery<StackTraceElement> threadStack(int takeCount) {
        //Thread.currentThread().getStackTrace()性能略差
        return NQuery.of(new Throwable().getStackTrace()).skip(2).take(takeCount);
    }

    public static Class<?> callerClass(int depth) {
        //Throwable.class.getDeclaredMethod("getStackTraceElement", int.class) & Reflection.getCallerClass(2 + depth) java 11 获取不到
        Class<?> type = SecurityManagerEx.instance.callerClass(2 + depth);
        log.debug("getCallerClass: {}", type.getName());
        return type;
    }

    @ErrorCode(messageKeys = {"$name", "$type"})
    public static InputStream getResource(Class owner, String name) {
        InputStream resource = owner.getResourceAsStream(name);
        if (resource == null) {
            throw new SystemException(values(owner, name));
        }
        return resource;
    }

    /**
     * ClassLoader.getSystemClassLoader()
     *
     * @return
     */
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
            throw SystemException.wrap(e);
        }
    }
    //endregion

    @SuppressWarnings(NON_WARNING)
    @SneakyThrows
    public static <T> T invokeDefaultMethod(Method method, Object instance, Object... args) {
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
        return method.getName().equals(closeMethod) && method.getParameterCount() == 0;
    }

    public static <T> T invokeMethod(Class type, Object instance, String name, Object... args) {
        Class<?>[] parameterTypes = ClassUtils.toClass(args);
        Method method = MethodUtils.getMatchingMethod(type, name, parameterTypes);
        if (method == null) {
            throw new SystemException("Parameters error");
        }
        return invokeMethod(method, instance, args);
    }

    @SuppressWarnings(NON_WARNING)
    @SneakyThrows
    public static <T> T invokeMethod(Method method, Object instance, Object... args) {
        setAccess(method);
        return (T) method.invoke(instance, args);
    }

//    @SuppressWarnings(NonWarning)
//    private static Object[] checkArgs(Class[] parameterTypes, Object... args) {
//        return NQuery.of(args).select((p, i) -> changeType(p, parameterTypes[i])).toArray();
//    }

    public static NQuery<PropertyNode> getProperties(Class to) {
        return Cache.getOrStore(Tuple.of("getProperties", to), tType -> {
            Method getClass = OBJECT_METHODS.first(p -> p.getName().equals("getClass"));
            NQuery<Method> q = NQuery.of(tType.right.getMethods());
            NQuery<Tuple<String, Method>> setters = q.where(p -> p.getName().startsWith(setProperty) && p.getParameterCount() == 1).select(p -> Tuple.of(propertyName(p.getName()), p));
            NQuery<Tuple<String, Method>> getters = q.where(p -> p != getClass && (p.getName().startsWith(getProperty) || p.getName().startsWith(getBoolProperty)) && p.getParameterCount() == 0).select(p -> Tuple.of(propertyName(p.getName()), p));
            return setters.join(getters.toList(), (p, x) -> p.left.equals(x.left), (p, x) -> new PropertyNode(p.left, p.right, x.right));
        }, CacheKind.WeakCache);
    }

    public static String propertyName(String getterOrSetterName) {
        require(getterOrSetterName);

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

        if (Character.isLowerCase(name.charAt(0))) {
            return name;
        }
        return name.substring(0, 1).toLowerCase() + name.substring(1);
    }

    @SuppressWarnings(NON_WARNING)
    @SneakyThrows
    public static <T> T readField(Class type, Object instance, String name) {
        Field field = getFields(type).where(p -> p.getName().equals(name)).first();
        return (T) field.get(instance);
    }

    @SneakyThrows
    public static <T> void writeField(Class type, Object instance, String name, T value) {
        Field field = getFields(type).where(p -> p.getName().equals(name)).first();
        field.set(instance, changeType(value, field.getType()));
    }

    public static NQuery<Field> getFields(Class type) {
        NQuery<Field> fields = NQuery.of(Cache.<Tuple<String, Class>, List<Field>>getOrStore(Tuple.of("getFields", type), k -> FieldUtils.getAllFieldsList(type), CacheKind.WeakCache));
        for (Field field : fields) {
            setAccess(field);
        }
        return fields;
    }

    public static <T> T newInstance(Class<T> type) {
        return newInstance(type, Arrays.EMPTY_OBJECT_ARRAY);
    }

    @SuppressWarnings(NON_WARNING)
    @SneakyThrows
    public static <T> T newInstance(Class<T> type, Object... args) {
        require(type);
        if (args == null) {
            args = Arrays.EMPTY_OBJECT_ARRAY;
        }

        try {
            return ConstructorUtils.invokeConstructor(type, args);
        } catch (Exception e) {
            log.warn("Not match any accessible constructors. {}", e.getMessage());
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                Class[] paramTypes = constructor.getParameterTypes();
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
        throw new SystemException("Parameters error");
    }

    private static void setAccess(AccessibleObject member) {
        try {
            if (!member.isAccessible()) {
                member.setAccessible(true);
            }
        } catch (Exception e) {
            log.warn("setAccess", e);
        }
    }

    public static <T> T convert(Object val, Class<T> toType) {
        return tryConvert(val, toType).right;
    }

    public static <T> Tuple<Boolean, T> tryConvert(Object val, Class<T> toType) {
        return tryConvert(val, toType, null);
    }

    public static <T> Tuple<Boolean, T> tryConvert(Object val, Class<T> toType, T defaultVal) {
        require(toType);

        try {
            return Tuple.of(true, Reflects.changeType(val, toType));
        } catch (Exception ex) {
            return Tuple.of(false, defaultVal);
        }
    }

    public static <TS, TT> void registerConvert(Class<TS> baseFromType, Class<TT> toType, BiFunction<TS, Class<TT>, TT> converter) {
        require(baseFromType, toType, converter);

        typeConverter.add(0, new ConvertBean<>(baseFromType, toType, converter));
        if (!supportTypes.contains(baseFromType)) {
            supportTypes.add(baseFromType);
        }
    }

    @ErrorCode(value = "notSupported", messageKeys = {"$fType", "$tType"})
    @ErrorCode(value = "enumError", messageKeys = {"$name", "$names", "$eType"})
    @ErrorCode(cause = NoSuchMethodException.class, messageKeys = {"$type"})
    @ErrorCode(cause = ReflectiveOperationException.class, messageKeys = {"$fType", "$tType", "$val"})
    public static <T> T changeType(Object value, Class<T> toType) {
        require(toType);

        if (value == null) {
            if (toType.isPrimitive()) {
                if (boolean.class.equals(toType)) {
                    value = false;
                } else {
                    value = 0;
                }
            } else {
                return null;
            }
        }
        if (Reflects.isInstance(value, toType)) {
            return (T) value;
        }
        NQuery<Class<?>> typeQuery = NQuery.of(supportTypes);
        Class<?> strType = typeQuery.first();
        if (toType.equals(strType)) {
            return (T) value.toString();
        }
        final Class<?> fromType = value.getClass();
        if (!(typeQuery.any(p -> ClassUtils.isAssignable(fromType, p)))) {
            throw new SystemException(values(fromType, toType), "notSupported");
        }
        Object fValue = value;
        Class<T> tType = toType;
        ConvertBean convertBean = NQuery.of(typeConverter).firstOrDefault(p -> Reflects.isInstance(fValue, p.getBaseFromType()) && p.getToType().isAssignableFrom(tType));
        if (convertBean != null) {
            return (T) convertBean.getConverter().apply(value, convertBean.getToType());
        }

        String val = value.toString();
        if (toType.equals(UUID.class)) {
            value = UUID.fromString(val);
        } else if (toType.equals(BigDecimal.class)) {
            value = new BigDecimal(val);
        } else if (toType.isEnum()) {
            NQuery<String> q = NQuery.of(toType.getEnumConstants()).select(p -> ((Enum) p).name());
            String fVal = val;
            value = q.where(p -> p.equals(fVal)).singleOrDefault();
            if (value == null) {
                throw new SystemException(values(val, String.join(",", q), toType.getSimpleName()), "enumError");
            }
        } else {
            try {
                toType = (Class) ClassUtils.primitiveToWrapper(toType);
                if (toType.equals(Boolean.class) && ClassUtils.isAssignable(fromType, Number.class)) {
                    if ("0".equals(val)) {
                        value = Boolean.FALSE;
                    } else if ("1".equals(val)) {
                        value = Boolean.TRUE;
                    } else {
                        throw new InvalidOperationException("Value should be 0 or 1");
                    }
                } else {
                    if (ClassUtils.isAssignable(toType, Number.class) && ClassUtils.primitiveToWrapper(fromType).equals(Boolean.class)) {
                        if (Boolean.FALSE.toString().equals(val)) {
                            val = "0";
                        } else if (Boolean.TRUE.toString().equals(val)) {
                            val = "1";
                        } else {
                            throw new InvalidOperationException("Value should be true or false");
                        }
                    }
                    Method m = toType.getDeclaredMethod("valueOf", strType);
                    value = m.invoke(null, val);
                }
            } catch (NoSuchMethodException ex) {
                throw new SystemException(values(toType), ex);
            } catch (ReflectiveOperationException ex) {
                throw new SystemException(values(fromType, toType, val), ex);
            }
        }
        return (T) value;
    }
}
