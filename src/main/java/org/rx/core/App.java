package org.rx.core;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.parser.Feature;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.alibaba.fastjson.serializer.ValueFilter;
import lombok.NonNull;
import lombok.SneakyThrows;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.SystemUtils;
import org.apache.commons.lang3.concurrent.CircuitBreakingException;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.rx.annotation.Description;
import org.rx.annotation.ErrorCode;
import org.rx.bean.*;
import org.rx.exception.ApplicationException;
import org.rx.exception.ExceptionHandler;
import org.rx.exception.ExceptionLevel;
import org.rx.exception.InvalidException;
import org.rx.io.*;
import org.rx.net.Sockets;
import org.rx.security.MD5Util;
import org.rx.bean.ProceedEventArgs;
import org.rx.util.function.*;
import org.slf4j.helpers.MessageFormatter;
import org.springframework.cglib.proxy.Enhancer;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import io.netty.util.internal.ThreadLocalRandom;

import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SuppressWarnings(Constants.NON_UNCHECKED)
public final class App extends SystemUtils {
    static final Pattern patternToFindOptions = Pattern.compile("(?<=-).*?(?==)");
    static final ValueFilter SKIP_TYPES_FILTER = (o, k, v) -> {
        if (v != null) {
            NQuery<Class<?>> q = NQuery.of(Container.get(RxConfig.class).getJsonSkipTypeSet());
            if (NQuery.couldBeCollection(v.getClass())) {
                List<Object> list = NQuery.asList(v, true);
                list.replaceAll(fv -> fv != null && q.any(t -> Reflects.isInstance(fv, t)) ? fv.getClass().getName() : fv);
                return list;
            }
            if (q.any(t -> Reflects.isInstance(v, t))) {
                return v.getClass().getName();
            }
        }
        return v;
    };
    static final String LOG_METRIC_PREFIX = "LM:";

    static {
        Container.register(RxConfig.class, readSetting("app", RxConfig.class), true);

        log("RxMeta {} {}_{}_{} @ {} & {}", JAVA_VERSION, OS_NAME, OS_VERSION, OS_ARCH, getBootstrapPath(), Sockets.getLocalAddresses());
    }

    public static java.io.File getJarFile(Object obj) {
        return getJarFile(obj.getClass());
    }

    @SneakyThrows
    public static java.io.File getJarFile(Class<?> _class) {
        String path = _class.getPackage().getName().replace(".", "/");
        String url = _class.getClassLoader().getResource(path).toString();
        url = url.replace(" ", "%20");
        java.net.URI uri = new java.net.URI(url);
        if (uri.getPath() == null) {
            path = uri.toString();
            if (path.startsWith("jar:file:")) {
                //Update Path and Define Zipped File
                path = path.substring(path.indexOf("file:/"));
                path = path.substring(0, path.toLowerCase().indexOf(".jar") + 4);

                if (path.startsWith("file://")) { //UNC Path
                    path = "C:/" + path.substring(path.indexOf("file:/") + 7);
                    path = "/" + new java.net.URI(path).getPath();
                } else {
                    path = new java.net.URI(path).getPath();
                }
                return new java.io.File(path);
            }
        } else {
            return new java.io.File(uri);
        }
        return null;
    }

    public static <T> T proxy(Class<T> type, @NonNull TripleFunc<Method, DynamicProxy, Object> func) {
        return proxy(type, func, false);
    }

    public static <T> T proxy(Class<T> type, @NonNull TripleFunc<Method, DynamicProxy, Object> func, boolean jdkProxy) {
        if (jdkProxy) {
            return (T) Proxy.newProxyInstance(Reflects.getClassLoader(), new Class[]{type}, new DynamicProxy(func));
        }
        return (T) Enhancer.create(type, new DynamicProxy(func));
    }

    public static String hashKey(String method, Object... args) {
        if (method == null) {
            method = Reflects.stackClass(1).getSimpleName();
        }
        if (!Arrays.isEmpty(args)) {
            method += java.util.Arrays.hashCode(args);
        }
        return method;
//        return method.intern();
    }

    //注意arg类型区分String和Number
    public static String cacheKey(String method, Object... args) {
        if (method == null) {
            method = Reflects.stackClass(1).getSimpleName();
        }
        if (!Arrays.isEmpty(args)) {
            method += Constants.CACHE_KEY_SUFFIX + toJsonString(args.length == 1 ? args[0] : args);
        }
        return method;
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

    public static Object[] getMessageCandidate(Object... args) {
        if (args != null && args.length != 0) {
            int lastIndex = args.length - 1;
            Object last = args[lastIndex];
            if (last instanceof Throwable) {
                if (lastIndex == 0) {
                    return Arrays.EMPTY_OBJECT_ARRAY;
                }
                return NQuery.of(args).take(lastIndex).toArray();
            }
        }
        return args;
    }

    public static Throwable getThrowableCandidate(Object... args) {
        return MessageFormatter.getThrowableCandidate(args);
    }

    public static String log(@NonNull String format, Object... args) {
        if (args == null) {
            args = Arrays.EMPTY_OBJECT_ARRAY;
        }
        org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(Reflects.stackClass(1));
        Throwable e = MessageFormatter.getThrowableCandidate(args);
        boolean isIgnoring = e == null;
        if (!isIgnoring) {
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

    public static void logHttp(@NonNull ProceedEventArgs eventArgs, String url) {
        log(eventArgs, msg -> {
            msg.appendLine("Url:\t%s %s", eventArgs.getTraceId(), url)
                    .appendLine("Request:\t%s", toJsonString(eventArgs.getParameters()));
            if (eventArgs.getError() != null) {
                msg.appendLine("Error:\t%s", eventArgs.getError());
            } else {
                msg.appendLine("Response:\t%s", toJsonString(eventArgs.getReturnValue()));
            }
        });
    }

    @SneakyThrows
    public static void log(@NonNull ProceedEventArgs eventArgs, @NonNull BiAction<StringBuilder> formatMessage) {
        Map<Object, Object> metrics = Cache.getInstance(Cache.THREAD_CACHE);
        boolean doWrite = !MapUtils.isEmpty(metrics);
        if (!doWrite) {
            if (eventArgs.getLogStrategy() == null) {
                eventArgs.setLogStrategy(eventArgs.getError() != null ? LogStrategy.WRITE_ON_ERROR : LogStrategy.WRITE_ON_NULL);
            }
            switch (eventArgs.getLogStrategy()) {
                case WRITE_ON_NULL:
                    doWrite = eventArgs.getError() != null
                            || (!eventArgs.isVoid() && eventArgs.getReturnValue() == null)
                            || (!Arrays.isEmpty(eventArgs.getParameters()) && Arrays.contains(eventArgs.getParameters(), null));
                    break;
                case WRITE_ON_ERROR:
                    if (eventArgs.getError() != null) {
                        doWrite = true;
                    }
                    break;
                case ALWAYS:
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

    //region Collection
    public static <T> List<T> newConcurrentList(boolean readMore) {
        return readMore ? new CopyOnWriteArrayList<>() : new Vector<>();
    }

    public static <T> List<T> newConcurrentList(int initialCapacity) {
        return newConcurrentList(initialCapacity, false);
    }

    public static <T> List<T> newConcurrentList(int initialCapacity, boolean readMore) {
        //CopyOnWriteArrayList 写性能差
        return readMore ? new CopyOnWriteArrayList<>() : new Vector<>(initialCapacity);
    }

    //final 字段不会覆盖
    public static <T> T fromJson(Object src, Type type) {
        String js = toJsonString(src);
        try {
            return JSON.parseObject(js, type, Feature.OrderedField);
        } catch (Exception e) {
            throw new InvalidException("fromJson %s", new Object[]{js}, e);
        }
    }

    public static JSONObject toJsonObject(Object src) {
        if (src instanceof JSONObject) {
            return (JSONObject) src;
        }
        if (src instanceof Map) {
            return new JSONObject((Map<String, Object>) src);
        }

        String js = toJsonString(src);
        try {
            return JSON.parseObject(js);
        } catch (Exception e) {
            throw new InvalidException("toJsonObject %s", new Object[]{js}, e);
        }
    }

    public static JSONArray toJsonArray(Object src) {
        if (src instanceof JSONArray) {
            return (JSONArray) src;
        }
        if (src instanceof List) {
            return new JSONArray((List<Object>) src);
        }

        String js = toJsonString(src);
        try {
            return JSON.parseArray(js);
        } catch (Exception e) {
            throw new InvalidException("toJsonArray %s", new Object[]{js}, e);
        }
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
            return JSON.toJSONString(SKIP_TYPES_FILTER.process(src, null, src), SerializerFeature.DisableCircularReferenceDetect);
        } catch (Throwable e) {
            NQuery<Object> q;
            if (NQuery.couldBeCollection(src.getClass())) {
                q = NQuery.ofCollection(src);
            } else {
                q = NQuery.of(src);
            }
            Set<Class<?>> jsonSkipTypeSet = Container.get(RxConfig.class).getJsonSkipTypeSet();
            jsonSkipTypeSet.addAll(q.where(p -> p != null && !p.getClass().getName().startsWith("java.")).select(Object::getClass).toSet());
            Container.get(ExceptionHandler.class).uncaughtException("toJsonString {}", NQuery.of(jsonSkipTypeSet).toJoinString(",", Class::getName), e);

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
                    log("sneakyInvoke retry={}", i, e);
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
                    log("sneakyInvoke retry={}", i, e);
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
            log("quietly", e);
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
            log("quietly", e);
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

    public static <T> void eachQuietly(Iterable<T> iterable, BiAction<T> fn) {
        if (iterable == null) {
            return;
        }

        for (T t : iterable) {
            try {
                fn.invoke(t);
            } catch (Throwable e) {
                if (e instanceof CircuitBreakingException) {
                    break;
                }
                log("eachQuietly", e);
            }
        }
    }

    public static boolean tryClose(Object obj) {
        return tryAs(obj, AutoCloseable.class, p -> quietly(p::close));
    }

    public static boolean tryClose(AutoCloseable obj) {
        if (obj == null) {
            return false;
        }
        quietly(obj::close);
        return true;
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

    //todo checkerframework
    @ErrorCode("test")
    public static void require(Object arg, boolean testResult) {
        if (!testResult) {
            throw new ApplicationException("test", values(arg));
        }
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

    public static <T> T as(Object obj, Class<T> type) {
        if (!Reflects.isInstance(obj, type)) {
            return null;
        }
        return (T) obj;
    }

    public static <T> boolean eq(T a, T b) {
        return (a == b) || (a != null && a.equals(b));
    }

    public static Object[] values(Object... args) {
        return args;
    }
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
            if (kBuf.length() > 0) {
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
        }).selectMany(yaml::loadAll)) {
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

    public static <T> String dumpYaml(@NonNull T bean) {
        Yaml yaml = new Yaml();
        return yaml.dump(bean);
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

                if (eat('^')) x = Math.pow(x, parseFactor()); // exponentiation

                return x;
            }
        }.parse();
    }
    //endregion

    //region codec
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

    //org.apache.commons.codec.binary.Base64.isBase64(base64String) 不准
    @SneakyThrows
    public static String convertToBase64String(byte[] data) {
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
