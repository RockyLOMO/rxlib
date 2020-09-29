package org.rx.core;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.parser.Feature;
import com.alibaba.fastjson.serializer.SerializerFeature;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import net.sf.cglib.proxy.Enhancer;
import net.sf.cglib.proxy.MethodInterceptor;
import net.sf.cglib.proxy.MethodProxy;
import org.rx.annotation.Description;
import org.rx.annotation.ErrorCode;
import org.rx.bean.RxConfig;
import org.rx.bean.SUID;
import org.rx.bean.Tuple;
import org.rx.core.exception.ApplicationException;
import org.rx.core.exception.InvalidException;
import org.rx.util.function.*;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

//入口，注意循环引用
@SuppressWarnings(Contract.NON_WARNING)
@Slf4j
public final class Contract {
    public static final String NON_WARNING = "all", UTF_8 = "UTF-8";
    public static final int TIMEOUT_INFINITE = -1, MAX_INT = Integer.MAX_VALUE - 8;
    public static final RxConfig CONFIG;
    private static final Set<Class> skipTypes = ConcurrentHashMap.newKeySet();

    static {
        CONFIG = isNull(readSetting("app", RxConfig.class), new RxConfig());
        if (CONFIG.getBufferSize() <= 0) {
            CONFIG.setBufferSize(512);
        }
        if (CONFIG.getNetMaxPoolSize() <= 0) {
            CONFIG.setNetMaxPoolSize(Runtime.getRuntime().availableProcessors() * 2);
        }
        skipTypes.addAll(CONFIG.getJsonSkipTypesEx());
    }

    //region basic
    @ErrorCode("arg")
    public static void require(Object arg) {
        if (arg == null) {
            throw new ApplicationException("arg", values());
        }
    }

    //区分 require((Object) T[]); 和 require(arg, (Object) boolean);
    @ErrorCode("args")
    public static void require(Object arg1, Object... args) {
        require(arg1);
        if (args == null || NQuery.of(args).any(Objects::isNull)) {
            throw new ApplicationException("args", values(toJsonString(args)));
        }
    }

    @ErrorCode("test")
    public static void require(Object arg, boolean testResult) {
        if (!testResult) {
            throw new ApplicationException("test", values(arg));
        }
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
    //endregion

    //region yml
    public static <T> T readSetting(String key) {
        return readSetting(key, null);
    }

    public static <T> T readSetting(String key, Class<T> type) {
        return readSetting(key, type, loadYaml("application.yml"));
    }

    public static <T> T readSetting(String key, Class<T> type, Map<String, Object> settings) {
        return readSetting(key, type, settings, false);
    }

    @ErrorCode("keyError")
    @ErrorCode("partialKeyError")
    public static <T> T readSetting(String key, Class<T> type, Map<String, Object> settings, boolean throwOnEmpty) {
        require(key, settings);

        Function<Object, T> func = p -> {
            if (type == null) {
                return (T) p;
            }
            Map<String, Object> map = as(p, Map.class);
            if (map != null) {
                return fromJson(map, type);
            }
            return Reflects.changeType(p, type);
        };
        Object val;
        if ((val = settings.get(key)) != null) {
            return func.apply(val);
        }

        StringBuilder kBuf = new StringBuilder();
        String d = ".";
        String[] splits = Strings.split(key, d);
        int c = splits.length - 1;
        for (int i = 0; i <= c; i++) {
            if (kBuf.getLength() > 0) {
                kBuf.append(d);
            }
            String k = kBuf.append(splits[i]).toString();
            if ((val = settings.get(k)) == null) {
                continue;
            }
            if (i == c) {
                return func.apply(val);
            }
            if ((settings = as(val, Map.class)) == null) {
                throw new ApplicationException("partialKeyError", values(k, type));
            }
            kBuf.setLength(0);
        }

        if (throwOnEmpty) {
            throw new ApplicationException("keyError", values(key, type));
        }
        return null;
    }

    @SneakyThrows
    public static Map<String, Object> loadYaml(String... yamlFile) {
        require(yamlFile);

        Map<String, Object> result = new HashMap<>();
        Yaml yaml = new Yaml(new SafeConstructor());
        for (String yf : yamlFile) {
            File file = new File(yf);
            for (Object data : yaml.loadAll(file.exists() ? new FileInputStream(file) : Reflects.getClassLoader().getResourceAsStream(yf))) {
                Map<String, Object> one = (Map<String, Object>) data;
                fillDeep(one, result);
            }
        }
        return result;
    }

    private static void fillDeep(Map<String, Object> one, Map<String, Object> all) {
        if (one == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : one.entrySet()) {
            Map<String, Object> nextOne;
            if ((nextOne = as(entry.getValue(), Map.class)) == null) {
                all.put(entry.getKey(), entry.getValue());
                continue;
            }
            Map<String, Object> nextAll = (Map<String, Object>) all.get(entry.getKey());
            if (nextAll == null) {
                all.put(entry.getKey(), nextOne);
                continue;
            }
            fillDeep(nextOne, nextAll);
        }
    }

    public static <T> T loadYaml(String yamlContent, Class<T> beanType) {
        require(yamlContent, beanType);

        Yaml yaml = new Yaml();
        return yaml.loadAs(yamlContent, beanType);
    }

    public static <T> String dumpYaml(T bean) {
        require(bean);

        Yaml yaml = new Yaml();
        return yaml.dump(bean);
    }
    //endregion

    //region extend
    @SneakyThrows
    public static <T> T retry(int retryCount, Func<T> func) {
        require(retryCount, retryCount >= 1);
        require(func);

        InvalidException lastEx = null;
        int i = 1;
        while (i <= retryCount) {
            try {
                return func.invoke();
            } catch (Exception ex) {
                if (i == retryCount) {
                    lastEx = InvalidException.wrap(ex);
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

    public static String cacheKey(String methodName, Object... args) {
        StringBuilder k = new StringBuilder(Reflects.stackClass(1).getSimpleName());
        int offset = 10;
        if (k.getLength() > offset) {
            k.setLength(offset);
        } else {
            offset = k.getLength();
        }
        k.append(methodName).append(toJsonString(args));
        if (k.getLength() <= 32) {
            return k.toString();
        }
        String hex = k.substring(offset);
        return k.setLength(offset).append(SUID.compute(hex)).toString();
    }

    public static String description(AnnotatedElement annotatedElement) {
        Description desc = annotatedElement.getAnnotation(Description.class);
        if (desc == null) {
            return null;
        }
        return desc.value();
    }

    public static boolean tryClose(Object obj) {
        return tryClose(obj, true);
    }

    public static boolean tryClose(Object obj, boolean quietly) {
        return tryAs(obj, AutoCloseable.class, quietly ? p -> catchCall(p::close) : AutoCloseable::close);
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

    public static <T> T proxy(Class<T> type, QuadraFunc<Method, Object[], Tuple<Object, MethodProxy>, Object> func) {
        require(type, func);

        return (T) Enhancer.create(type, (MethodInterceptor) (proxyObject, method, args, methodProxy) -> func.invoke(method, args, Tuple.of(proxyObject, methodProxy)));
    }

    public static void sleep() {
        sleep(CONFIG.getSleepMillis());
    }

    @SneakyThrows
    public static void sleep(long millis) {
        Thread.sleep(millis);
    }
    //endregion

    //region delegate
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
    //final 字段不会覆盖
    public static <T> T fromJson(Object src, Type type) {
        return JSON.parseObject(toJsonString(src), type, Feature.OrderedField);
    }

    public static JSONObject toJsonObject(Object src) {
        return JSON.parseObject(toJsonString(src));
    }

    public static JSONArray toJsonArray(Object src) {
        return JSON.parseArray(toJsonString(src));
    }

    public static String toJsonString(Object src) {
        if (src == null) {
            return "{}";
        }
        String s;
        if ((s = as(src, String.class)) != null) {
            return s;
        }

        Function<Object, String> skipResult = p -> p.getClass().getName();
        Class type = src.getClass();
        List<Object> jArr = null;
        try {
            if (type.isArray() || src instanceof Iterable) {
                jArr = NQuery.asList(src);
                for (int i = 0; i < jArr.size(); i++) {
                    Object p = jArr.get(i);
                    if (p != null && NQuery.of(skipTypes).any(p2 -> Reflects.isInstance(p, p2))) {
                        jArr.set(i, skipResult.apply(p));
                    }
                }
                src = jArr;
            } else {
                Object p = src;
                if (NQuery.of(skipTypes).any(p2 -> Reflects.isInstance(p, p2))) {
                    src = skipResult.apply(p);
                }
            }
            return JSON.toJSONString(src, SerializerFeature.DisableCircularReferenceDetect);  //gson map date not work
        } catch (Exception ex) {
            NQuery<Object> q;
            if (jArr != null) {
                q = NQuery.of(jArr);
            } else {
                q = NQuery.of(src);
            }
            skipTypes.addAll(q.where(p -> p != null && !p.getClass().getName().startsWith("java.")).select(Object::getClass).toSet());
            log.warn("toJsonString {}", NQuery.of(skipTypes).toJoinString(",", Class::getName), ex);

            JSONObject json = new JSONObject();
            json.put("_input", src.toString());
            json.put("_error", ex.getMessage());
            return json.toString();
        }
    }
    //endregion
}
