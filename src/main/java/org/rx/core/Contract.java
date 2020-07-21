package org.rx.core;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.google.gson.*;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import net.sf.cglib.proxy.Enhancer;
import net.sf.cglib.proxy.MethodInterceptor;
import net.sf.cglib.proxy.MethodProxy;
import org.rx.annotation.Description;
import org.rx.annotation.ErrorCode;
import org.rx.bean.Tuple;
import org.rx.security.MD5Util;
import org.rx.util.function.*;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.rx.core.App.Config;

@Slf4j
public final class Contract {
    public static final String NonWarning = "all", Utf8 = "UTF-8";
    public static final int TimeoutInfinite = -1, MaxInt = Integer.MAX_VALUE - 8;
    private static NQuery<Class> skipTypes = NQuery.of();

    //App循环引用
    static void init() {
        String[] jsonSkipTypes = Config.getJsonSkipTypes();
        if (!Arrays.isEmpty(jsonSkipTypes)) {
            skipTypes = skipTypes.union(NQuery.of(jsonSkipTypes).select(p -> App.loadClass(String.valueOf(p), false)));
        }
    }

    @ErrorCode(value = "arg")
    public static void require(Object arg) {
        if (arg == null) {
            throw new SystemException(values(), "arg");
        }
    }

    //区分 require((Object) T[]); 和 require(arg, (Object) boolean);
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

    //region extend
    public static String cacheKey(String methodName, Object... args) {
        require(methodName);

        String k = Reflects.callerClass(1).getName() + methodName + toJsonString(args);
        if (k.length() <= 32) {
            return k;
        }
        return MD5Util.md5Hex(k);
    }

    @SneakyThrows
    public static <T> T retry(int retryCount, Func<T> func) {
        require(func);

        SystemException lastEx = null;
        int i = 1;
        while (i <= retryCount) {
            try {
                return func.invoke();
            } catch (Exception ex) {
                if (i == retryCount) {
                    lastEx = SystemException.wrap(ex);
                }
            }
            i++;
        }
        throw lastEx;
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
        return tryAs(obj, AutoCloseable.class, !quietly ? AutoCloseable::close : p -> catchCall(p::close));
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

    @SuppressWarnings(NonWarning)
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

    public static String toDescription(AnnotatedElement annotatedElement) {
        Description desc = annotatedElement.getAnnotation(Description.class);
        if (desc == null) {
            return null;
        }
        return desc.value();
    }

    public static void sleep() {
        sleep(Config.getSleepMillis());
    }

    @SneakyThrows
    public static void sleep(long millis) {
        Thread.sleep(millis);
    }

    public static <T> T proxy(Class<T> type, QuadraFunc<Method, Object[], Tuple<Object, MethodProxy>, Object> func) {
        return (T) Enhancer.create(type, (MethodInterceptor) (proxyObject, method, args, methodProxy) -> func.invoke(method, args, Tuple.of(proxyObject, methodProxy)));
    }
    //endregion

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

    //region json
    public static <T> T fromJsonAsObject(Object jsonOrBean, Class<T> type) {
//        Gson gson = gson();
//        if (jsonOrBean instanceof String) {
//            return gson.fromJson((String) jsonOrBean, type);
//        }
//        return gson.fromJson(gson.toJsonTree(jsonOrBean), type);
        return JSON.parseObject(toJsonString(jsonOrBean), type);
//        return JSON.toJavaObject(jsonOrBean, type);
//        return JSON.parseObject(toJsonString(jsonOrBean)).toJavaObject(type);
    }

    public static <T> T fromJsonAsObject(Object jsonOrBean, Type type) {
        Gson gson = gson();
        if (jsonOrBean instanceof String) {
            return gson.fromJson((String) jsonOrBean, type);
        }
        return gson.fromJson(gson.toJsonTree(jsonOrBean), type);
    }

    public static <T> List<T> fromJsonAsList(Object jsonOrList, Class<T> type) {
        //gson.fromJson() not work
        return JSON.parseArray(toJsonString(jsonOrList), type);
    }

    public static <T> List<T> fromJsonAsList(Object jsonOrList, Type type) {
        //(List<T>) JSON.parseArray(toJsonString(jsonOrList), new Type[]{type}); not work
        Gson gson = gson();
        if (jsonOrList instanceof String) {
            return gson.fromJson((String) jsonOrList, type);
        }
        return gson.fromJson(gson.toJsonTree(jsonOrList), type);
    }

    public static JSONObject toJsonObject(Object jsonOrBean) {
        return JSON.parseObject(toJsonString(jsonOrBean));
    }

    public static JSONArray toJsonArray(Object jsonOrList) {
        return JSON.parseArray(toJsonString(jsonOrList));
    }

    public static String toJsonString(Object bean) {
        if (bean == null) {
            return "{}";
        }
        String s;
        if ((s = as(bean, String.class)) != null) {
            return s;
        }

        Function<Object, String> skipResult = p -> p.getClass().getName();
        Class type = bean.getClass();
        List<Object> jArr = null;
        try {
            if (type.isArray() || bean instanceof Iterable) {
                jArr = NQuery.asList(bean);
                for (int i = 0; i < jArr.size(); i++) {
                    Object p = jArr.get(i);
                    if (p != null && skipTypes.any(p2 -> Reflects.isInstance(p, p2))) {
                        jArr.set(i, skipResult.apply(p));
                    }
                }
                bean = jArr;
            } else {
                Object p = bean;
                if (skipTypes.any(p2 -> Reflects.isInstance(p, p2))) {
                    bean = skipResult.apply(p);
                }
            }
            return JSON.toJSONString(bean);  //gson map date not work
        } catch (Exception ex) {
            NQuery<Object> q;
            if (jArr != null) {
                q = NQuery.of(jArr);
            } else {
                q = NQuery.of(bean);
            }
            skipTypes = skipTypes.union(q.where(p -> p != null && !p.getClass().getName().startsWith("java."))
                    .<Class>select(Object::getClass).distinct());
            log.warn("toJsonString {}", skipTypes.toJoinString(",", Class::getName), ex);

            JSONObject json = new JSONObject();
            json.put("_input", bean.toString());
            json.put("_error", ex.getMessage());
            return json.toString();
        }
    }

    private static Gson gson() {
        return new GsonBuilder().registerTypeAdapter(Date.class, (JsonSerializer<Date>) (date, type, jsonSerializationContext) -> date == null ? null : new JsonPrimitive(date.getTime()))
                .registerTypeAdapter(Date.class, (JsonDeserializer<Date>) (jsonElement, type, jsonDeserializationContext) -> jsonElement == null ? null : new Date(jsonElement.getAsLong())).create();
    }
    //endregion
}
