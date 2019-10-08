package org.rx.core;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.reflect.FieldUtils;

import java.lang.reflect.*;
import java.util.List;

import static org.rx.core.Contract.require;

/**
 * sun.reflect.Reflection.getCallerClass()
 */
@Slf4j
public class Reflects {
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

    public static <T> T newInstance(Class<T> type) {
        return newInstance(type, Arrays.EMPTY_OBJECT_ARRAY);
    }

    @SneakyThrows
    public static <T> T newInstance(Class<T> type, Object... args) {
        require(type);
        if (args == null) {
            args = Arrays.EMPTY_OBJECT_ARRAY;
        }

        for (Constructor<?> constructor : type.getConstructors()) {
            Class[] paramTypes = constructor.getParameterTypes();
            if (paramTypes.length != args.length) {
                continue;
            }
            boolean ok = true;
            for (int i = 0; i < paramTypes.length; i++) {
                if (!paramTypes[i].isInstance(args[i])) {
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
        throw new SystemException("Parameters error");
    }

    public static NQuery<Field> getFields(Class type) {
        NQuery<Field> fields = NQuery.of((List<Field>) WeakCache.getInstance().getOrAdd("Reflects.getFields", k -> FieldUtils.getAllFieldsList(type)));
        for (Field field : fields) {
            setAccess(field);
        }
        return fields;
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
