package org.rx;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

import java.util.Arrays;
import java.util.function.Function;
import java.util.function.Supplier;

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

    @ErrorCode(value = "test", messageKeys = { "$arg" })
    public static void require(Object arg, boolean testResult) {
        if (!testResult) {
            throw new SystemException(values(arg), "test");
        }
    }

    public static <T> T as(Object obj, Class<T> type) {
        if (!type.isInstance(obj)) {
            return null;
        }
        return (T) obj;
    }

    public static <T> boolean eq(T t1, T t2) {
        if (t1 == null) {
            if (t2 == null) {
                return true;
            }
            return false;
        }
        return t1.equals(t2);
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

    public static Object[] values(Object... args) {
        return args;
    }

    public static <T, TR> boolean tryGet($<TR> out, Function<T, TR> func) {
        return tryGet(out, func, null);
    }

    public static <T, TR> boolean tryGet($<TR> out, Function<T, TR> func, T state) {
        require(out, func);

        return (out.$ = func.apply(state)) != null;
    }

    public static String toJSONString(Object... args) {
        return toJSONString((Object) args);
    }

    public static String toJSONString(Object arg) {
        if (arg == null) {
            return "{}";
        }
        String s;
        if ((s = as(arg, String.class)) != null) {
            return s;
        }

        try {
            return JSON.toJSONString(arg);
        } catch (Exception ex) {
            JSONObject json = new JSONObject();
            json.put("_input", arg.toString());
            json.put("_error", ex.getMessage());
            return json.toJSONString();
        }
    }
}
