package org.rx.core;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Constructor;

import static org.rx.core.Contract.require;

/**
 * sun.reflect.Reflection.getCallerClass()
 */
@Slf4j
public class Reflects {
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
