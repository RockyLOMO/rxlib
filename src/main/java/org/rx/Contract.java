package org.rx;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.rx.bean.$;

import java.lang.reflect.AccessibleObject;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;

public final class Contract {
    public interface SettingNames {
        String JsonSkipTypes = "app.jsonSkipTypes";
        String ErrorCodeFiles = "app.errorCodeFiles";
    }

    public static final int DefaultBufferSize;
    public static final String SettingsFile, EmptyString, Utf8, AllWarnings = "all";
    public static final Object[] EmptyArray;
    private static NQuery<Class> SkipTypes = NQuery.of();

    static {
        DefaultBufferSize = 1024;
        SettingsFile = "application";
        EmptyString = "";
        Utf8 = "UTF-8";
        EmptyArray = new Object[0];

        Object val = App.readSetting(SettingNames.JsonSkipTypes);
        if (val != null) {
            SkipTypes = SkipTypes
                    .union(NQuery.of(App.asList(val)).select(p -> App.loadClass(String.valueOf(p), false)));
        }
    }

    @ErrorCode(value = "arg")
    public static void require(Object arg) {
        if (arg == null) {
            throw new SystemException(values(), "arg");
        }
    }

    @ErrorCode(value = "args", messageKeys = {"$args"})
    public static void require(Object... args) {
        if (args == null || Arrays.stream(args).anyMatch(p -> p == null)) {
            throw new SystemException(values(toJsonString(args)), "args");
        }
    }

    @ErrorCode(value = "test", messageKeys = {"$arg"})
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

    public static String toDescription(AccessibleObject accessibleObject) {
        Description desc = accessibleObject.getAnnotation(Description.class);
        if (desc == null) {
            return null;
        }
        return desc.value();
    }

    public static String toJsonString(Object arg) {
        if (arg == null) {
            return "{}";
        }
        String s;
        if ((s = as(arg, String.class)) != null) {
            return s;
        }

        Function<Object, String> skipResult = p -> p.getClass().getName();
        Class type = arg.getClass();
        List jArr = null;
        Map<Object, Object> jObj = null;
        try {
            if (type.isArray() || arg instanceof Iterable) {
                jArr = App.asList(arg);
                for (int i = 0; i < jArr.size(); i++) {
                    Object p = jArr.get(i);
                    if (SkipTypes.any(p2 -> p2.isInstance(p))) {
                        jArr.set(i, skipResult.apply(p));
                    }
                }
                arg = jArr;
            } else if ((jObj = as(arg, Map.class)) != null) {
                for (Map.Entry<Object, Object> kv : jObj.entrySet()) {
                    Object p = kv.getValue();
                    if (SkipTypes.any(p2 -> p2.isInstance(p))) {
                        jObj.put(kv.getKey(), skipResult.apply(p));
                    }
                }
                arg = jObj;
            } else {
                Object p = arg;
                if (SkipTypes.any(p2 -> p2.isInstance(p))) {
                    arg = skipResult.apply(p);
                }
            }
            return JSON.toJSONString(arg);
        } catch (Exception ex) {
            NQuery q;
            if (jArr != null) {
                q = NQuery.of(jArr);

            } else if (jObj != null) {
                q = NQuery.of(jObj.values());
            } else {
                q = NQuery.of(arg);
            }
            SkipTypes = SkipTypes.union(q.where(p -> p != null && !p.getClass().getName().startsWith("java."))
                    .select(p -> p.getClass()).distinct());

            JSONObject json = new JSONObject();
            json.put("_input", arg.toString());
            json.put("_error", ex.getMessage());
            return json.toJSONString();
        }
    }
}
