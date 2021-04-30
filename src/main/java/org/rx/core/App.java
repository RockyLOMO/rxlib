package org.rx.core;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.parser.Feature;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.alibaba.fastjson.serializer.ValueFilter;
import lombok.Setter;
import lombok.SneakyThrows;
import net.sf.cglib.proxy.Enhancer;
import net.sf.cglib.proxy.MethodInterceptor;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.SystemUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.rx.annotation.Description;
import org.rx.annotation.ErrorCode;
import org.rx.bean.*;
import org.rx.core.exception.ApplicationException;
import org.rx.core.exception.ExceptionLevel;
import org.rx.core.exception.InvalidException;
import org.rx.io.IOStream;
import org.rx.security.MD5Util;
import org.rx.io.MemoryStream;
import org.rx.bean.ProceedEventArgs;
import org.rx.spring.SpringContext;
import org.rx.util.function.*;
import org.slf4j.helpers.MessageFormatter;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static java.lang.Math.pow;

@SuppressWarnings(App.NON_WARNING)
public final class App extends SystemUtils {
    public static final String NON_WARNING = "all", UTF_8 = "UTF-8", CACHE_KEY_SUFFIX = ":";
    public static final int TIMEOUT_INFINITE = -1, MAX_INT = Integer.MAX_VALUE - 8;
    static final ValueFilter skipTypesFilter = new ValueFilter() {
        @Override
        public Object process(Object o, String k, Object v) {
            if (v != null) {
                //import do not invoke getConfig()
                NQuery<Class<?>> q = config == null ? NQuery.of() : NQuery.of(config.getJsonSkipTypeSet());
                if (v.getClass().isArray() || v instanceof Iterable) {
                    List<Object> list = NQuery.asList(v);
                    list.replaceAll(fv -> fv != null && q.any(t -> Reflects.isInstance(fv, t)) ? fv.getClass().getName() : fv);
                    return list;
                }
                if (q.any(t -> Reflects.isInstance(v, t))) {
                    return v.getClass().getName();
                }
            }
            return v;
        }
    };
    private static volatile RxConfig config;
    @Setter
    private static volatile Predicate<Throwable> ignoreExceptionHandler;

    public static synchronized RxConfig getConfig() {
        if (SpringContext.isInitiated()) {
            return SpringContext.getBean(RxConfig.class);
        }
        if (config == null) {
            config = readSetting("app", RxConfig.class);
            config.init();
        }
        return config;
    }

    //region Contract
    //region basic
    @ErrorCode("arg")
    public static void require(Object arg) {
        if (arg == null) {
            throw new ApplicationException("arg", values());
        }
    }

    //区分 require((Object) T[]); 和 require(arg, (Object) boolean);
    @ErrorCode("args")
    public static void require(Object arg, Object... args) {
        require(arg);
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

    public static String cacheKey(String methodName, Object... args) {
        StringBuilder k = new StringBuilder();
        if (Strings.endsWith(methodName, CACHE_KEY_SUFFIX)) {
            k.append(methodName);
        } else {
            k.append("%s.%s", Reflects.stackClass(1).getSimpleName(), methodName).append(CACHE_KEY_SUFFIX);
        }
        return k.append(SUID.compute(toJsonString(args))).toString();
    }

    @SneakyThrows
    public static void sleep(long millis) {
        Thread.sleep(millis);
    }
    //endregion

    //region more
    //final 字段不会覆盖
    public static <T> T fromJson(Object src, Type type) {
        try {
            return JSON.parseObject(toJsonString(src), type, Feature.OrderedField);
        } catch (Exception e) {
            throw new InvalidException("fromJson %s", new Object[]{toJsonString(src)}, e);
        }
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
//            return JSON.toJSONString(src, skipTypesFilter, SerializerFeature.DisableCircularReferenceDetect);
            return JSON.toJSONString(skipTypesFilter.process(src, null, src), SerializerFeature.DisableCircularReferenceDetect);
        } catch (Throwable e) {
            NQuery<Object> q;
            if (src.getClass().isArray() || src instanceof Iterable) {
                q = NQuery.of(NQuery.asList(src));
            } else {
                q = NQuery.of(src);
            }
            Set<Class<?>> jsonSkipTypeSet = getConfig().getJsonSkipTypeSet();
            jsonSkipTypeSet.addAll(q.where(p -> p != null && !p.getClass().getName().startsWith("java.")).select(Object::getClass).toSet());
            log("toJsonString {}", NQuery.of(jsonSkipTypeSet).toJoinString(",", Class::getName), e);

            JSONObject json = new JSONObject();
            json.put("_input", src.toString());
            json.put("_error", e.getMessage());
            return json.toString();
        }
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
                    App.log("sneakyInvoke retry={}", i, e);
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
                    App.log("sneakyInvoke retry={}", i, e);
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
    //endregion

    //region Basic
    public static String getBootstrapPath() {
        return new File("").getAbsolutePath();
    }

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
        Yaml yaml = new Yaml();
        for (Object data : NQuery.of(yamlFile).selectMany(p -> {
            NQuery<InputStream> resources = Reflects.getResources(p);
            if (resources.any()) {
                return resources;
            }
            File file = new File(p);
            return file.exists() ? Arrays.toList(new FileInputStream(file)) : Collections.emptyList();
        }).selectMany(p -> yaml.loadAll(p))) {
            Map<String, Object> one = (Map<String, Object>) data;
            fillDeep(one, result);
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

    public static boolean isIgnoringException(Throwable e) {
        if (e == null) {
            return false;
        }
        return ignoreExceptionHandler != null && ignoreExceptionHandler.test(e);
    }

    public static String log(String format, Object... args) {
        if (args == null) {
            args = Arrays.EMPTY_OBJECT_ARRAY;
        }
        org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(Reflects.stackClass(1));
        Throwable e = MessageFormatter.getThrowableCandidate(args);
        boolean isIgnoring = e == null;
        if (!isIgnoring && (isIgnoring = isIgnoringException(e))) {
            format += "\t{}";
            args[args.length - 1] = e.getMessage();
        }
        if (isIgnoring) {
            log.info(format, args);
            return ApplicationException.getMessage(e);
        }

        InvalidException invalidException = as(e, InvalidException.class);
        if (invalidException == null || invalidException.getLevel() == null || invalidException.getLevel() == ExceptionLevel.SYSTEM) {
            log.error(format, args);
            Tasks.raiseUncaughtException(e);
        } else {
            format += "\t{}";
            args[args.length - 1] = e.getMessage();
            log.warn(format, args);
        }
        return ApplicationException.getMessage(e);
    }

    @SneakyThrows
    public static void log(ProceedEventArgs eventArgs, BiFunc<ProceedEventArgs, String> formatMessage) {
        boolean doWrite = false;
        switch (isNull(eventArgs.getLogStrategy(), LogStrategy.WriteOnNull)) {
            case WriteOnNull:
                doWrite = eventArgs.getError() != null
                        || (!eventArgs.isVoid() && eventArgs.getReturnValue() == null)
                        || (!Arrays.isEmpty(eventArgs.getParameters()) && Arrays.contains(eventArgs.getParameters(), null));
                break;
            case WriteOnError:
                if (eventArgs.getError() != null) {
                    doWrite = true;
                }
                break;
            case Always:
                doWrite = true;
                break;
        }
        if (doWrite) {
            List<String> whitelist = eventArgs.getLogTypeWhitelist();
            if (!CollectionUtils.isEmpty(whitelist)) {
                doWrite = NQuery.of(whitelist).any(p -> eventArgs.getDeclaringType().getName().startsWith(p));
            }
        }
        if (doWrite) {
            org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(eventArgs.getDeclaringType());
            String msg = formatMessage.invoke(eventArgs);
            if (eventArgs.getError() != null) {
                log.error(msg, eventArgs.getError());
            } else {
                log.info(msg);
            }
        }
    }

    /**
     * 简单的计算字符串
     *
     * @param expression 字符串
     * @return 计算结果
     */
    public static double simpleEval(final String expression) {
        return new Object() {
            int pos = -1, ch;

            void nextChar() {
                ch = (++pos < expression.length()) ? expression.charAt(pos) : -1;
            }

            boolean eat(int charToEat) {
                while (ch == ' ') nextChar();
                if (ch == charToEat) {
                    nextChar();
                    return true;
                }
                return false;
            }

            double parse() {
                nextChar();
                double x = parseExpression();
                if (pos < expression.length()) throw new RuntimeException("Unexpected: " + (char) ch);
                return x;
            }

            // Grammar:
            // expression = term | expression `+` term | expression `-` term
            // term = factor | term `*` factor | term `/` factor
            // factor = `+` factor | `-` factor | `(` expression `)`
            //        | number | functionName factor | factor `^` factor

            double parseExpression() {
                double x = parseTerm();
                for (; ; ) {
                    if (eat('+')) x += parseTerm(); // addition
                    else if (eat('-')) x -= parseTerm(); // subtraction
                    else return x;
                }
            }

            double parseTerm() {
                double x = parseFactor();
                for (; ; ) {
                    if (eat('*')) x *= parseFactor(); // multiplication
                    else if (eat('/')) x /= parseFactor(); // division
                    else return x;
                }
            }

            double parseFactor() {
                if (eat('+')) return parseFactor(); // unary plus
                if (eat('-')) return -parseFactor(); // unary minus

                double x;
                int startPos = this.pos;

                if (eat('(')) { // parentheses
                    x = parseExpression();
                    eat(')');
                } else if ((ch >= '0' && ch <= '9') || ch == '.') { // numbers
                    while ((ch >= '0' && ch <= '9') || ch == '.') nextChar();
                    x = Double.parseDouble(expression.substring(startPos, this.pos));
                } else throw new RuntimeException("Unexpected: " + (char) ch);

                if (eat('^')) x = pow(x, parseFactor()); // exponentiation

                return x;
            }
        }.parse();
    }

    public static UUID hash(Object... args) {
        return hash(Strings.joinWith(Strings.EMPTY, args));
    }

    public static UUID hash(String key) {
        require(key);

        byte[] guidBytes = MD5Util.md5(key);
        return SUID.newUUID(guidBytes);
    }

    public static UUID combId() {
        return combId(System.nanoTime(), null);
    }

    public static UUID combId(Timestamp timestamp, String key) {
        return combId(timestamp.getTime(), key);
    }

    public static UUID combId(long timestamp, String key) {
        return combId(timestamp, key, false);
    }

    //http://www.codeproject.com/Articles/388157/GUIDs-as-fast-primary-keys-under-multiple-database
    public static UUID combId(long timestamp, String key, boolean sequentialAtEnd) {
        long id;
        if (key != null) {
            id = ByteBuffer.wrap(MD5Util.md5(key)).getLong(4);
        } else {
            id = ThreadLocalRandom.current().nextLong();
        }
        long mostSigBits, leastSigBits;
        if (sequentialAtEnd) {
            mostSigBits = id;
            leastSigBits = timestamp;
        } else {
            mostSigBits = timestamp;
            leastSigBits = id;
        }
        return new UUID(mostSigBits, leastSigBits);
    }
    //endregion

    //region Base64
    //org.apache.commons.codec.binary.Base64.isBase64(base64String) 不准
    @SneakyThrows
    public static String convertToBase64String(byte[] data) {
        require(data);

        byte[] ret = Base64.getEncoder().encode(data);
        return new String(ret, StandardCharsets.UTF_8);
    }

    @SneakyThrows
    public static byte[] convertFromBase64String(String base64) {
        require(base64);

        byte[] data = base64.getBytes(StandardCharsets.UTF_8);
        return Base64.getDecoder().decode(data);
    }

    public static <T extends Serializable> String serializeToBase64(T obj) {
        byte[] data = IOStream.serialize(obj).toArray();
        return convertToBase64String(data);
    }

    public static <T extends Serializable> T deserializeFromBase64(String base64) {
        byte[] data = convertFromBase64String(base64);
        return IOStream.deserialize(new MemoryStream(data, 0, data.length));
    }

    public static <T extends Serializable> T deepClone(T obj) {
        IOStream<?, ?> serialize = IOStream.serialize(obj);
        return IOStream.deserialize(serialize);
    }
    //endregion
}
