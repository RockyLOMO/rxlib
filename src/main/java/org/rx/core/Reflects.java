package org.rx.core;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ClassUtils;
import org.apache.commons.lang3.reflect.ConstructorUtils;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.commons.lang3.reflect.MethodUtils;
import org.apache.commons.lang3.reflect.TypeUtils;
import org.rx.bean.Tuple;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.*;
import java.util.List;

import static org.rx.core.Contract.*;

@Slf4j
public class Reflects extends TypeUtils {
    @RequiredArgsConstructor
    public static class PropertyNode {
        public final String propertyName;
        public final Method setter;
        public final Method getter;
    }

    static class SecurityManagerEx extends SecurityManager {
        static SecurityManagerEx instance = new SecurityManagerEx();

        Class callerClass(int depth) {
            return getClassContext()[depth];
        }
    }

    public static final NQuery<Method> ObjectMethods = NQuery.of(Object.class.getMethods());
    private static final String getProperty = "get", getBoolProperty = "is", setProperty = "set", closeMethod = "close";
    private static final Constructor<MethodHandles.Lookup> lookupConstructor;
    private static final int lookupFlags = MethodHandles.Lookup.PUBLIC | MethodHandles.Lookup.PROTECTED | MethodHandles.Lookup.PRIVATE | MethodHandles.Lookup.PACKAGE;

    static {
        try {
            lookupConstructor = MethodHandles.Lookup.class.getDeclaredConstructor(Class.class, int.class);
            setAccess(lookupConstructor);
        } catch (NoSuchMethodException e) {
            throw SystemException.wrap(e);
        }
    }

    public static NQuery<PropertyNode> getProperties(Class to) {
        return MemoryCache.getOrStore(to, tType -> {
            Method getClass = ObjectMethods.first(p -> p.getName().equals("getClass"));
            NQuery<Method> q = NQuery.of(tType.getMethods());
            NQuery<Tuple<String, Method>> setters = q.where(p -> p.getName().startsWith(setProperty) && p.getParameterCount() == 1).select(p -> Tuple.of(propertyName(p.getName()), p));
            NQuery<Tuple<String, Method>> getters = q.where(p -> p != getClass && (p.getName().startsWith(getProperty) || p.getName().startsWith(getBoolProperty)) && p.getParameterCount() == 0).select(p -> Tuple.of(propertyName(p.getName()), p));
            return setters.join(getters.toList(), (p, x) -> p.left.equals(x.left), (p, x) -> new PropertyNode(p.left, p.right, x.right));
        }, CacheKind.SoftCache);
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

//    @SuppressWarnings(NonWarning)
//    private static Object[] checkArgs(Class[] parameterTypes, Object... args) {
//        return NQuery.of(args).select((p, i) -> App.changeType(p, parameterTypes[i])).toArray();
//    }

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

    @SuppressWarnings(NonWarning)
    @SneakyThrows
    public static <T> T invokeDefaultMethod(Method method, Object instance, Object... args) {
        require(method, method.isDefault());
        Class<?> declaringClass = method.getDeclaringClass();
        return (T) lookupConstructor.newInstance(declaringClass, lookupFlags)
                .unreflectSpecial(method, declaringClass)
                .bindTo(instance)
                .invokeWithArguments(args);
    }

    public static boolean invokeClose(Method method, Object obj) {
        if (!isCloseMethod(method)) {
            return false;
        }
        return tryClose(obj);
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

    @SuppressWarnings(NonWarning)
    @SneakyThrows
    public static <T> T invokeMethod(Method method, Object instance, Object... args) {
        setAccess(method);
        return (T) method.invoke(instance, args);
    }

    @SuppressWarnings(NonWarning)
    @SneakyThrows
    public static <T> T readField(Class type, Object instance, String name) {
        Field field = getFields(type).where(p -> p.getName().equals(name)).first();
        return (T) field.get(instance);
    }

    @SneakyThrows
    public static <T> void writeField(Class type, Object instance, String name, T value) {
        Field field = getFields(type).where(p -> p.getName().equals(name)).first();
        field.set(instance, App.changeType(value, field.getType()));
    }

    public static NQuery<Field> getFields(Class type) {
        NQuery<Field> fields = NQuery.of(MemoryCache.<String, List<Field>>getOrStore(cacheKey("getFields", type), k -> FieldUtils.getAllFieldsList(type)));
        for (Field field : fields) {
            setAccess(field);
        }
        return fields;
    }

    public static <T> T newInstance(Class<T> type) {
        return newInstance(type, Arrays.EMPTY_OBJECT_ARRAY);
    }

    @SuppressWarnings(NonWarning)
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
}
