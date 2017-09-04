package org.rx.common;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.serializer.SerializerFeature;

import java.util.Arrays;
import java.util.function.Predicate;

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

    public static String toJSONString(Object... args) {
        try {
            return JSON.toJSONString(args, SerializerFeature.DisableCircularReferenceDetect);
        } catch (Exception ex) {
            return String.format("[\"Contract.toJSONString:%s\"]", ex.getMessage());
        }
    }
}
