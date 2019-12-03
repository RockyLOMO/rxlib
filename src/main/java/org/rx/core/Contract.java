package org.rx.core;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.annotation.Description;
import org.rx.annotation.ErrorCode;
import org.rx.security.MD5Util;
import org.rx.util.function.Action;
import org.rx.util.function.BiAction;
import org.rx.util.function.Func;

import java.lang.reflect.AccessibleObject;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.rx.core.App.Config;

@Slf4j
public final class Contract {
    public static final String AllWarnings = "all", Utf8 = "UTF-8";
    private static NQuery<Class> SkipTypes = NQuery.of();

    //App循环引用
    static void init() {
        String[] jsonSkipTypes = Config.getJsonSkipTypes();
        if (!Arrays.isEmpty(jsonSkipTypes)) {
            SkipTypes = SkipTypes.union(NQuery.of(NQuery.asList(jsonSkipTypes)).select(p -> App.loadClass(String.valueOf(p), false)));
        }
    }

    @ErrorCode(value = "arg")
    public static void require(Object arg) {
        if (arg == null) {
            throw new SystemException(values(), "arg");
        }
    }

    /**
     * require((Object) T[]);
     * require(arg, (Object) boolean);
     *
     * @param args
     */
    @ErrorCode(value = "args", messageKeys = {"$args"})
    public static void require(Object... args) {
        if (args == null || NQuery.of(args).any(Objects::isNull)) {
            throw new SystemException(values(toJsonString(args)), "args");
        }
    }

    @ErrorCode(value = "test", messageKeys = {"$arg"})
    public static void require(Object arg, boolean testResult) {
        if (!testResult) {
            throw new SystemException(values(arg), "test");
        }
    }

    //region Delegate
    public static <TSender extends EventTarget<TSender>, TArgs extends EventArgs> BiConsumer<TSender, TArgs> combine(BiConsumer<TSender, TArgs> a, BiConsumer<TSender, TArgs> b) {
        if (a == null) {
            require(b);
            return wrap(b);
        }
        EventTarget.Delegate<TSender, TArgs> aw = wrap(a);
        if (b == null) {
            return aw;
        }
        if (b instanceof EventTarget.Delegate) {
            aw.getInvocationList().addAll(((EventTarget.Delegate<TSender, TArgs>) b).getInvocationList());
        } else {
            aw.getInvocationList().add(b);
        }
        return aw;
    }

    private static <TSender extends EventTarget<TSender>, TArgs extends EventArgs> EventTarget.Delegate<TSender, TArgs> wrap(BiConsumer<TSender, TArgs> a) {
        if (a instanceof EventTarget.Delegate) {
            return (EventTarget.Delegate<TSender, TArgs>) a;
        }
        EventTarget.Delegate<TSender, TArgs> delegate = new EventTarget.Delegate<>();
        delegate.getInvocationList().add(a);
        return delegate;
    }

    public static <TSender extends EventTarget<TSender>, TArgs extends EventArgs> BiConsumer<TSender, TArgs> remove(BiConsumer<TSender, TArgs> a, BiConsumer<TSender, TArgs> b) {
        if (a == null) {
            if (b == null) {
                return null;
            }
            return wrap(b);
        }
        EventTarget.Delegate<TSender, TArgs> aw = wrap(a);
        if (b == null) {
            return aw;
        }
        if (b instanceof EventTarget.Delegate) {
            aw.getInvocationList().removeAll(((EventTarget.Delegate<TSender, TArgs>) b).getInvocationList());
        } else {
            aw.getInvocationList().remove(b);
        }
        return aw;
    }
    //endregion

    public static String cacheKey(String methodName, Object... args) {
        require(methodName);

        String k = methodName + toJsonString(args);
        if (k.length() <= 32) {
            return k;
        }
        return MD5Util.md5Hex(k);
    }

    public static boolean catchCall(Action action) {
        require(action);

        try {
            action.invoke();
            return true;
        } catch (Throwable e) {
            log.warn("catchCall", e);
        }
        return false;
    }

    public static <T> T catchCall(Func<T> action) {
        require(action);

        try {
            return action.invoke();
        } catch (Throwable e) {
            log.warn("catchCall", e);
        }
        return null;
    }

    public static boolean tryClose(Object obj) {
        return tryClose(obj, true);
    }

    public static boolean tryClose(Object obj, boolean quietly) {
        return tryAs(obj, AutoCloseable.class, quietly ? AutoCloseable::close : p -> catchCall(p::close));
    }

    public static void sleep() {
        sleep(Config.getScheduleDelay());
    }

    @SneakyThrows
    public static void sleep(long millis) {
        Thread.sleep(millis);
    }

    public static <T> boolean tryAs(Object obj, Class<T> type) {
        return tryAs(obj, type, null);
    }

    @SneakyThrows
    public static <T> boolean tryAs(Object obj, Class<T> type, BiAction<T> action) {
        T t = as(obj, type);
        if (t == null) {
            return false;
        }
        if (action != null) {
            action.invoke(t);
        }
        return true;
    }

    public static <T> T as(Object obj, Class<T> type) {
        if (!Reflects.isInstance(obj, type)) {
            return null;
        }
        return (T) obj;
    }

    public static <T> boolean eq(T t1, T t2) {
        if (t1 == null) {
            return t2 == null;
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
                jArr = NQuery.asList(arg);
                for (int i = 0; i < jArr.size(); i++) {
                    Object p = jArr.get(i);
                    if (SkipTypes.any(p2 -> Reflects.isInstance(p, p2))) {
                        jArr.set(i, skipResult.apply(p));
                    }
                }
                arg = jArr;
            } else if ((jObj = as(arg, Map.class)) != null) {
                for (Map.Entry<Object, Object> kv : jObj.entrySet()) {
                    Object p = kv.getValue();
                    if (SkipTypes.any(p2 -> Reflects.isInstance(p, p2))) {
                        jObj.put(kv.getKey(), skipResult.apply(p));
                    }
                }
                arg = jObj;
            } else {
                Object p = arg;
                if (SkipTypes.any(p2 -> Reflects.isInstance(p, p2))) {
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
