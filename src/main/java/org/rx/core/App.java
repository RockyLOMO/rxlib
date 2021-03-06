package org.rx.core;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.parser.Feature;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.alibaba.fastjson.serializer.ValueFilter;
import lombok.NonNull;
import lombok.Setter;
import lombok.SneakyThrows;
import net.sf.cglib.proxy.Enhancer;
import net.sf.cglib.proxy.MethodInterceptor;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.SystemUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.rx.annotation.Description;
import org.rx.annotation.ErrorCode;
import org.rx.bean.*;
import org.rx.core.exception.ApplicationException;
import org.rx.core.exception.ExceptionLevel;
import org.rx.core.exception.InvalidException;
import org.rx.io.*;
import org.rx.security.MD5Util;
import org.rx.bean.ProceedEventArgs;
import org.rx.spring.SpringContext;
import org.rx.util.function.*;
import org.slf4j.helpers.MessageFormatter;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.lang.Math.pow;

@SuppressWarnings(App.NON_WARNING)
public final class App extends SystemUtils {
    public static final String NON_WARNING = "all", CACHE_KEY_SUFFIX = ":";
    public static final int TIMEOUT_INFINITE = -1;
    static final Pattern patternToFindOptions = Pattern.compile("(?<=-).*?(?==)");
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
    @Setter
    static volatile Predicate<Throwable> ignoreExceptionHandler;
    static final String LOG_METRIC_PREFIX = "LM:";
    private static RxConfig config;

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
    public static <T> T proxy(@NonNull Class<T> type, @NonNull TripleFunc<Method, InterceptProxy, Object> func) {
        return (T) Enhancer.create(type, (MethodInterceptor) (proxyObject, method, args, methodProxy) -> func.invoke(method, new InterceptProxy(proxyObject, methodProxy, args)));
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

    public static String description(@NonNull AnnotatedElement annotatedElement) {
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

    public static <T> T getBean(@NonNull Class<T> type) {
        return Container.getInstance().get(type);
    }

    public static <T> void registerBean(@NonNull Class<T> type, @NonNull T instance) {
        Container.getInstance().register(type, instance);
    }

    //region json
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

    public static boolean sneakyInvoke(@NonNull Action action, int retryCount) {
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

    public static <T> T sneakyInvoke(@NonNull Func<T> action, int retryCount) {
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

    public static boolean quietly(@NonNull Action action) {
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

    public static <T> T quietly(@NonNull Func<T> action, Func<T> defaultValue) {
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

    public static boolean tryClose(Object obj) {
        return tryClose(obj, true);
    }

    public static boolean tryClose(Object obj, boolean quietly) {
        return tryAs(obj, AutoCloseable.class, quietly ? p -> quietly(p::close) : AutoCloseable::close);
    }

    //todo checkerframework
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

    //region delegate
    public static <TSender extends EventTarget<TSender>, TArgs extends EventArgs> BiConsumer<TSender, TArgs> combine(BiConsumer<TSender, TArgs> a, BiConsumer<TSender, TArgs> b) {
        if (a == null) {
            Objects.requireNonNull(b);
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
    public static List<String> argsOperations(String[] args) {
        List<String> result = new ArrayList<>();
        for (String arg : args) {
            if (arg.startsWith("-")) {
                break;
            }
            result.add(arg);
        }
        return result;
    }

    public static Map<String, String> argsOptions(String[] args) {
        Map<String, String> result = new HashMap<>();
        for (String arg : args) {
            if (arg.startsWith("-")) {
                Matcher matcher = patternToFindOptions.matcher(arg);
                if (matcher.find()) {
                    result.put(matcher.group(), arg.replaceFirst("-.*?=", ""));
                }
            }
        }
        return result;
    }

    public static MainArgs parseArgs(String[] args) {
        return new MainArgs(argsOperations(args), argsOptions(args));
    }

    public static String getBootstrapPath() {
        return new File(Strings.EMPTY).getAbsolutePath();
    }

    public static <T> T readSetting(String key) {
        return readSetting(key, null);
    }

    public static <T> T readSetting(String key, Class<T> type) {
        return readSetting(key, type, loadYaml("application.yml"), false);
    }

    @ErrorCode("keyError")
    @ErrorCode("partialKeyError")
    public static <T> T readSetting(@NonNull String key, Class<T> type, @NonNull Map<String, Object> settings, boolean throwOnEmpty) {
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
    public static Map<String, Object> loadYaml(@NonNull String... yamlFile) {
        Map<String, Object> result = new HashMap<>();
        Yaml yaml = new Yaml();
        for (Object data : NQuery.of(yamlFile).selectMany(p -> {
            NQuery<InputStream> resources = Reflects.getResources(p);
            if (resources.any()) {
                return resources.reverse();
            }
            File file = new File(p);
            return file.exists() ? Arrays.toList(new FileStream(file).getReader()) : Collections.emptyList();
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

    public static <T> T loadYaml(@NonNull String yamlContent, @NonNull Class<T> beanType) {
        Yaml yaml = new Yaml();
        return yaml.loadAs(yamlContent, beanType);
    }

    public static <T> String dumpYaml(@NonNull T bean) {
        Yaml yaml = new Yaml();
        return yaml.dump(bean);
    }

    public static boolean isIgnoringException(Throwable e) {
        if (e == null) {
            return false;
        }
        return ignoreExceptionHandler != null && ignoreExceptionHandler.test(e);
    }

    public static String log(@NonNull String format, Object... args) {
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
        } else {
            format += "\t{}";
            args[args.length - 1] = e.getMessage();
            log.warn(format, args);
        }
        return ApplicationException.getMessage(e);
    }

    public static void logMetric(String name, Object value) {
        Cache.getInstance(Cache.THREAD_CACHE).put(LOG_METRIC_PREFIX + name, value);
    }

    public static void logApi(@NonNull ProceedEventArgs eventArgs, String url) {
        log(eventArgs, msg -> {
            msg.appendLine("CallApi:\t%s %s", eventArgs.getTraceId(), url)
                    .appendLine("Request:\t%s", toJsonString(eventArgs.getParameters()))
                    .append("Response:\t%s", toJsonString(eventArgs.getReturnValue()));
        });
    }

    @SneakyThrows
    public static void log(@NonNull ProceedEventArgs eventArgs, @NonNull BiAction<StringBuilder> formatMessage) {
        Map<Object, Object> metrics = Cache.getInstance(Cache.THREAD_CACHE);
        boolean doWrite = !MapUtils.isEmpty(metrics);
        if (!doWrite) {
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
        }
        if (doWrite) {
            List<String> whitelist = eventArgs.getLogTypeWhitelist();
            if (!CollectionUtils.isEmpty(whitelist)) {
                doWrite = NQuery.of(whitelist).any(p -> eventArgs.getDeclaringType().getName().startsWith(p));
            }
        }
        if (doWrite) {
            org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(eventArgs.getDeclaringType());
            StringBuilder msg = new StringBuilder(RxConfig.HEAP_BUF_SIZE);
            formatMessage.invoke(msg);
            boolean first = true;
            for (Map.Entry<Object, Object> entry : metrics.entrySet()) {
                String key;
                if ((key = as(entry.getKey(), String.class)) == null || !Strings.startsWith(key, LOG_METRIC_PREFIX)) {
                    continue;
                }
                if (first) {
                    msg.append("Metrics:\t");
                    first = false;
                }
                msg.append("%s=%s ", key.substring(LOG_METRIC_PREFIX.length()), toJsonString(entry.getValue()));
            }
            if (!first) {
                msg.appendLine();
            }
            if (eventArgs.getError() != null) {
//                log.error(msg.toString(), eventArgs.getError());
                log(msg.toString(), eventArgs.getError());
            } else {
                log.info(msg.toString());
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

    public static UUID hash(@NonNull String key) {
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
            id = Bytes.getLong(MD5Util.md5(key), 4);
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
    public static String convertToBase64String(@NonNull byte[] data) {
        byte[] ret = Base64.getEncoder().encode(data);
        return new String(ret, StandardCharsets.UTF_8);
    }

    @SneakyThrows
    public static byte[] convertFromBase64String(@NonNull String base64) {
        byte[] data = base64.getBytes(StandardCharsets.UTF_8);
        return Base64.getDecoder().decode(data);
    }

    public static <T extends Serializable> String serializeToBase64(T obj) {
        byte[] data = Serializer.DEFAULT.serialize(obj).toArray();
        return convertToBase64String(data);
    }

    public static <T extends Serializable> T deserializeFromBase64(String base64) {
        byte[] data = convertFromBase64String(base64);
        return Serializer.DEFAULT.deserialize(new MemoryStream(data, 0, data.length));
    }

    public static <T extends Serializable> T deepClone(T obj) {
        IOStream<?, ?> stream = Serializer.DEFAULT.serialize(obj);
        return Serializer.DEFAULT.deserialize(stream.rewind());
    }
    //endregion
}
