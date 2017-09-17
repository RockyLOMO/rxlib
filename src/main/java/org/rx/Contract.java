package org.rx;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.serializer.SerializerFeature;

import java.util.Arrays;
import java.util.function.Supplier;

import static org.rx.SystemException.values;

public final class Contract {
    @ErrorCode(value = "arg")
    public static void require(Object arg) {
        if (arg == null) {
            throw new SystemException(values(), "arg");
        }
    }

    @ErrorCode(value = "args", messageKeys = { "$args" })
    public static void require(Object... args) {
        if (args == null || Arrays.stream(args).anyMatch(p -> p == null)) {
            throw new SystemException(values(toJSONString(args)), "args");
        }
    }

    @ErrorCode(value = "test", messageKeys = { "$obj" })
    public static void require(Object instance, boolean testResult) {
        if (!testResult) {
            throw new SystemException(values(instance), "test");
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

    public static String toJSONString(Object... args) {
        return toJSONString((Object) args);
    }

    public static String toJSONString(Object arg) {
        if (arg == null) {
            return "{}";
        }

        try {
            return JSON.toJSONString(arg, SerializerFeature.DisableCircularReferenceDetect);
        } catch (Exception ex) {
            JSONObject json = new JSONObject();
            json.put("_input", arg.toString());
            json.put("_error", ex.getMessage());
            return json.toJSONString();
        }
    }
}
