package org.rx.core;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ClassUtils;
import org.apache.commons.lang3.reflect.ConstructorUtils;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.commons.lang3.reflect.MethodUtils;
import org.apache.commons.lang3.reflect.TypeUtils;

import java.lang.reflect.*;
import java.util.List;

import static org.rx.core.Contract.*;

/**
 * sun.reflect.Reflection.getCallerClass()
 */
@Slf4j
public class Reflects extends TypeUtils {
    @RequiredArgsConstructor
    public static class PropertyCacheNode {
        public final NQuery<Method> setters;
        public final NQuery<Method> getters;
    }

    public static final NQuery<Method> ObjectMethods = NQuery.of(Object.class.getMethods());
    private static final String getProperty = "get", getBoolProperty = "is", setProperty = "set", closeMethod = "close";

    public static PropertyCacheNode getProperties(Class to) {
        return MemoryCache.getOrStore(to, tType -> {
            Method getClass = ObjectMethods.first(p -> p.getName().equals("getClass"));
            NQuery<Method> q = NQuery.of(tType.getMethods());
            NQuery<Method> setters = q.where(p -> p.getName().startsWith(setProperty) && p.getParameterCount() == 1);
            NQuery<Method> getters = q.where(p -> p != getClass && (p.getName().startsWith(getProperty) || p.getName().startsWith(getBoolProperty)) && p.getParameterCount() == 0);
            NQuery<Method> s2 = setters.where(ps -> getters.any(pg -> propertyEquals(pg.getName(), ps.getName())));
            NQuery<Method> g2 = getters.where(pg -> s2.any(ps -> propertyEquals(pg.getName(), ps.getName())));
            return new PropertyCacheNode(s2, g2);
        }, CacheKind.SoftCache);
    }

    public static boolean propertyEquals(String getterName, String setterName) {
        String name;
        if (setterName.startsWith(getProperty)) {
            name = setterName.substring(getProperty.length());
        } else if (setterName.startsWith(getBoolProperty)) {
            name = setterName.substring(getBoolProperty.length());
        } else if (setterName.startsWith(setProperty)) {
            name = setterName.substring(setProperty.length());
        } else {
            name = Strings.toTitleCase(setterName);
        }
        return getterName.substring(getterName.startsWith(getProperty) ? getProperty.length() : getBoolProperty.length()).equals(name);
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

    @SneakyThrows
    public static void fillProperties(Object instance, Object propBean) {
        require(instance, propBean);

        NQuery<Method> methods = NQuery.of(instance.getClass().getMethods());
        for (Field field : getFields(propBean.getClass())) {
            Object val = field.get(propBean);
            if (val == null) {
                continue;
            }
            String methodName = String.format("set%s", Strings.toTitleCase(field.getName()));
            Method method = methods.where(p -> p.getName().equals(methodName)).firstOrDefault();
            if (method == null) {
                continue;
            }
            try {
                method.invoke(instance, checkArgs(method.getParameterTypes(), val));
            } catch (Exception e) {
                log.warn("fillProperties", e);
            }
        }
    }

    private static Object[] checkArgs(Class[] parameterTypes, Object... args) {
        return NQuery.of(args).select((p, i) -> App.changeType(p, parameterTypes[i])).toArray();
    }

    public static Object invokeMethod(Class type, Object instance, String name, Object... args) {
        Class<?>[] parameterTypes = ClassUtils.toClass(args);
        Method method = MethodUtils.getMatchingMethod(type, name, parameterTypes);
        if (method == null) {
            throw new SystemException("Parameters error");
        }
        return invokeMethod(method, instance, args);
    }

    @SneakyThrows
    public static Object invokeMethod(Method method, Object instance, Object... args) {
        setAccess(method);
        return method.invoke(instance, args);
    }

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
        NQuery<Field> fields = NQuery.of(MemoryCache.<String, List<Field>>getOrStore(cacheKey(Reflects.class, "getFields", type), k -> FieldUtils.getAllFieldsList(type)));
        for (Field field : fields) {
            setAccess(field);
        }
        return fields;
    }

    public static <T> T newInstance(Class<T> type) {
        return newInstance(type, Arrays.EMPTY_OBJECT_ARRAY);
    }

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
