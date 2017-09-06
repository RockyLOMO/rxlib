package org.rx.common;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.serializer.SerializerFeature;

import java.util.Arrays;
import java.util.function.Predicate;
import java.util.function.Supplier;

public class Contract {
    public static void require(Object... args) {
        if (args == null || Arrays.stream(args).anyMatch(p -> p == null)) {
            throw new IllegalArgumentException(String.format("Args[%s] anyMatch null", toJSONString(args)));
        }
    }

    public static void require(Object instance, boolean ok) {
        if (!ok) {
            throw new IllegalArgumentException(
                    String.format("Instance[%s] test failed", instance.getClass().getSimpleName()));
        }
    }

    public static <T> void require(T arg, Predicate<T> func) {
        if (!func.test(arg)) {
            throw new IllegalArgumentException(String.format("Arg[%s] test failed", arg));
        }
    }

    public static <T> T as(Object obj, Class<T> type) {
        if (!type.isInstance(obj)) {
            return null;
        }
        return (T) obj;
    }

    public static <T> T isNull(T value, T defaultVal) {
        if (value == null) {
            return defaultVal;
        }
        return value;
    }

    public static <T> T isNull(T value, Supplier<T> supplier) {
        if (value == null) {
            if (supplier != null) {
                value = supplier.get();
            }
        }
        return value;
    }

    public static InvalidOperationException newCause(String format, Object... args) {
        return wrapCause(null, format, args);
    }

    public static InvalidOperationException wrapCause(Throwable ex) {
        return isNull(as(ex, InvalidOperationException.class), () -> new InvalidOperationException(ex));
    }

    public static InvalidOperationException wrapCause(Throwable ex, String format, Object... args) {
        if (format != null) {
            format = String.format(format, args);
        }
        String message = format;
        return new InvalidOperationException(message, ex);
    }

    public static String toJSONString(Object... args) {
        try {
            return JSON.toJSONString(args, SerializerFeature.DisableCircularReferenceDetect);
        } catch (Exception ex) {
            return String.format("[\"Contract.toJSONString:%s\"]", ex.getMessage());
        }
    }
}
