package org.rx.core;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.parser.Feature;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.alibaba.fastjson.serializer.ValueFilter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import net.sf.cglib.proxy.Enhancer;
import net.sf.cglib.proxy.MethodInterceptor;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.rx.annotation.Description;
import org.rx.annotation.ErrorCode;
import org.rx.bean.InterceptProxy;
import org.rx.bean.RxConfig;
import org.rx.bean.SUID;
import org.rx.core.exception.ApplicationException;
import org.rx.util.function.*;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

@SuppressWarnings(Contract.NON_WARNING)
@Slf4j
public final class Contract {
    public static final String NON_WARNING = "all", UTF_8 = "UTF-8";
    public static final int TIMEOUT_INFINITE = -1, MAX_INT = Integer.MAX_VALUE - 8;

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

    public static String description(AnnotatedElement annotatedElement) {
        Description desc = annotatedElement.getAnnotation(Description.class);
        if (desc == null) {
            return null;
        }
        return desc.value();
    }

    @SneakyThrows
    public static void sleep(long millis) {
        Thread.sleep(millis);
    }
    //endregion

    //region more
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
            quietly(() -> {
                File file = new File(yf);
                for (Object data : yaml.loadAll(file.exists() ? new FileInputStream(file) : Reflects.getClassLoader().getResourceAsStream(yf))) {
                    Map<String, Object> one = (Map<String, Object>) data;
                    fillDeep(one, result);
                }
            });
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
    //endregion

    //region extend
    public static boolean sneakyInvoke(Action action) {
        return sneakyInvoke(action, 1);
    }

    public static boolean sneakyInvoke(Action action, int retryCount) {
        require(action);

        Throwable last = null;
        for (int i = 0; i < retryCount; i++) {
            try {
                action.invoke();
                return true;
            } catch (Throwable e) {
                if (last != null) {
                    log.warn("sneakyInvoke retry={} error={} {}", i, e.getClass().getName(), e.getMessage());
                }
                last = e;
            }
        }
        if (last != null) {
            ExceptionUtils.rethrow(last);
        }
        return false;
    }

    public static <T> T sneakyInvoke(Func<T> action) {
        return sneakyInvoke(action, 1);
    }

    public static <T> T sneakyInvoke(Func<T> action, int retryCount) {
        require(action);

        Throwable last = null;
        for (int i = 0; i < retryCount; i++) {
            try {
                return action.invoke();
            } catch (Throwable e) {
                if (last != null) {
                    log.warn("sneakyInvoke retry={} error={} {}", i, e.getClass().getName(), e.getMessage());
                }
                last = e;
            }
        }
        if (last != null) {
            ExceptionUtils.rethrow(last);
        }
        return null;
    }

    public static boolean quietly(Action action) {
        require(action);

        try {
            action.invoke();
            return true;
        } catch (Throwable e) {
            App.log("quietly", e);
        }
        return false;
    }

    public static <T> T quietly(Func<T> action) {
        return quietly(action, null);
    }

    public static <T> T quietly(Func<T> action, Func<T> defaultValue) {
        require(action);

        try {
            return action.invoke();
        } catch (Throwable e) {
            App.log("quietly", e);
        }
        if (defaultValue != null) {
            try {
                return defaultValue.invoke();
            } catch (Throwable e) {
                ExceptionUtils.rethrow(e);
            }
        }
        return null;
    }

    public static boolean tryClose(Object obj) {
        return tryClose(obj, true);
    }

    public static boolean tryClose(Object obj, boolean quietly) {
        return tryAs(obj, AutoCloseable.class, quietly ? p -> quietly(p::close) : AutoCloseable::close);
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

    public static <T> T proxy(Class<T> type, TripleFunc<Method, InterceptProxy, Object> func) {
        require(type, func);

        return (T) Enhancer.create(type, (MethodInterceptor) (proxyObject, method, args, methodProxy) -> func.invoke(method, new InterceptProxy(proxyObject, methodProxy, args)));
    }

    public static <T> T getBean(Class<T> type) {
        return Container.getInstance().get(type);
    }

    public static <T> void registerBean(Class<T> type, T instance) {
        Container.getInstance().register(type, instance);
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

        try {
            return JSON.toJSONString(src, new ValueFilter() {
                @Override
                public Object process(Object o, String k, Object v) {
                    if (v != null && NQuery.of(RxConfig.jsonSkipSet).any(p -> Reflects.isInstance(v, p))) {
                        return v.getClass().getName();
                    }
                    return v;
                }
            }, SerializerFeature.DisableCircularReferenceDetect);
        } catch (Exception e) {
            NQuery<Object> q;
            if (src.getClass().isArray() || src instanceof Iterable) {
                q = NQuery.of(NQuery.asList(src));
            } else {
                q = NQuery.of(src);
            }
            RxConfig.jsonSkipSet.addAll(q.where(p -> p != null && !p.getClass().getName().startsWith("java.")).select(Object::getClass).toSet());
            log.warn("toJsonString {}", NQuery.of(RxConfig.jsonSkipSet).toJoinString(",", Class::getName), e);

            JSONObject json = new JSONObject();
            json.put("_input", src.toString());
            json.put("_error", e.getMessage());
            return json.toString();
        }
    }
    //endregion
}
