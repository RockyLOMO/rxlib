package org.rx.core;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.parser.Feature;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.alibaba.fastjson.serializer.ValueFilter;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.SystemUtils;
import org.rx.bean.*;
import org.rx.codec.CrcModel;
import org.rx.exception.ExceptionHandler;
import org.rx.exception.InvalidException;
import org.rx.io.*;
import org.rx.net.Sockets;
import org.rx.bean.ProceedEventArgs;
import org.rx.util.function.*;
import org.springframework.cglib.proxy.Enhancer;

import java.io.*;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.*;

import io.netty.util.internal.ThreadLocalRandom;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.rx.core.Extends.as;

@Slf4j
@SuppressWarnings(Constants.NON_UNCHECKED)
public final class App extends SystemUtils {
    static final String DPR = "_DPR";
    static final Pattern PATTERN_TO_FIND_OPTIONS = Pattern.compile("(?<=-).*?(?==)");
    static final ValueFilter SKIP_TYPES_FILTER = (o, k, v) -> {
        if (v != null) {
            NQuery<Class<?>> q = NQuery.of(RxConfig.INSTANCE.jsonSkipTypes);
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
    static final Feature[] PARSE_FLAGS = new Feature[]{Feature.OrderedField};
    static final String LOG_METRIC_PREFIX = "LM:";

    static {
        RxConfig conf = RxConfig.INSTANCE;
        Container.register(Cache.class, Container.<Cache>get(conf.cache.mainInstance));

        log.info("RxMeta {} {}_{}_{} @ {} & {}\n{}", JAVA_VERSION, OS_NAME, OS_VERSION, OS_ARCH,
                new File(Strings.EMPTY).getAbsolutePath(), Sockets.getLocalAddresses(), JSON.toJSONString(conf));
    }

    public static File getJarFile(Object obj) {
        return getJarFile(obj.getClass());
    }

    @SneakyThrows
    public static File getJarFile(Class<?> klass) {
        String path = klass.getPackage().getName().replace(".", "/");
        String url = klass.getClassLoader().getResource(path).toString();
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
                return new File(path);
            }
        } else {
            return new File(uri);
        }
        return null;
    }

    public static <T> T rawObject(Object proxyObject) {
        return Extends.<String, T>weakMap(proxyObject).get(DPR);
    }

    public static <T> T proxy(Class<?> type, @NonNull TripleFunc<Method, DynamicProxy, Object> func) {
        return proxy(type, func, false);
    }

    public static <T> T proxy(Class<?> type, @NonNull TripleFunc<Method, DynamicProxy, Object> func, boolean jdkProxy) {
        return proxy(type, func, null, jdkProxy);
    }

    public static <T> T proxy(Class<?> type, @NonNull TripleFunc<Method, DynamicProxy, Object> func, T rawObject, boolean jdkProxy) {
        T proxyObj;
        if (jdkProxy) {
            proxyObj = (T) Proxy.newProxyInstance(Reflects.getClassLoader(), new Class[]{type}, new DynamicProxy(func));
        } else {
            proxyObj = (T) Enhancer.create(type, new DynamicProxy(func));
        }
        if (rawObject != null) {
            Extends.weakMap(proxyObj).put(DPR, rawObject);
        }
        return proxyObj;
    }

    public static <T> ArrayList<T> proxyList(ArrayList<T> source, BiAction<ArrayList<T>> onSet) {
        return proxy(ArrayList.class, (m, p) -> {
            Object val = p.fastInvoke(source);
            if (onSet != null && Reflects.List_WRITE_METHOD_NAMES.contains(m.getName())) {
                onSet.invoke(source);
            }
            return val;
        });
    }

    public static void logExtra(String name, Object value) {
        Cache.getInstance(Cache.THREAD_CACHE).put(LOG_METRIC_PREFIX + name, value);
    }

    public static void logHttp(@NonNull ProceedEventArgs eventArgs, String url) {
        RxConfig conf = RxConfig.INSTANCE;
        eventArgs.setLogStrategy(conf.logStrategy);
        eventArgs.setLogTypeWhitelist(conf.logTypeWhitelist);
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
        Map<Object, Object> extra = Cache.getInstance(Cache.THREAD_CACHE);
        boolean doWrite = !MapUtils.isEmpty(extra);
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
            Set<String> whitelist = eventArgs.getLogTypeWhitelist();
            if (!CollectionUtils.isEmpty(whitelist)) {
                doWrite = NQuery.of(whitelist).any(p -> eventArgs.getDeclaringType().getName().startsWith(p));
            }
        }
        if (doWrite) {
            org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(eventArgs.getDeclaringType());
            StringBuilder msg = new StringBuilder(Constants.HEAP_BUF_SIZE);
            formatMessage.invoke(msg);
            boolean first = true;
            for (Map.Entry<Object, Object> entry : extra.entrySet()) {
                String key;
                if ((key = as(entry.getKey(), String.class)) == null || !Strings.startsWith(key, LOG_METRIC_PREFIX)) {
                    continue;
                }
                if (first) {
                    msg.append("Extra:\t");
                    first = false;
                }
                msg.append("%s=%s ", key.substring(LOG_METRIC_PREFIX.length()), toJsonString(entry.getValue()));
            }
            if (!first) {
                msg.appendLine();
            }
            if (eventArgs.getError() != null) {
                ExceptionHandler.INSTANCE.log(msg.toString(), eventArgs.getError());
            } else {
                log.info(msg.toString());
            }
        }
    }

    //region basic
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
                Matcher matcher = PATTERN_TO_FIND_OPTIONS.matcher(arg);
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

    //region json
    //final 字段不会覆盖
    public static <T> T fromJson(Object src, Type type) {
        String js = toJsonString(src);
        try {
            return JSON.parseObject(js, type, PARSE_FLAGS);
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
            Set<Class<?>> jsonSkipTypes = RxConfig.INSTANCE.jsonSkipTypes;
            jsonSkipTypes.addAll(q.where(p -> p != null && !p.getClass().getName().startsWith("java.")).select(Object::getClass).toSet());
            ExceptionHandler.INSTANCE.log("toJsonString {}", NQuery.of(jsonSkipTypes).toJoinString(",", Class::getName), e);

            JSONObject json = new JSONObject();
            json.put("_input", src.toString());
            json.put("_error", e.getMessage());
            return json.toString();
        }
    }
    //endregion

    //region codec
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

    public static String cacheKey(String method, Object... args) {
        return cacheKey(null, method, args);
    }

    //注意arg类型区分String和Number
    public static String cacheKey(String region, String method, Object... args) {
        if (region == null) {
            region = Strings.EMPTY;
        }
        StringBuilder buf = new StringBuilder(region);
        if (method == null) {
            method = Reflects.stackClass(1).getSimpleName();
        }
        buf.append(Constants.CACHE_KEY_SUFFIX).append(method);
        if (!Arrays.isEmpty(args)) {
            buf.append(Constants.CACHE_KEY_SUFFIX).append(hash64(args));
        }
        return buf.toString();
    }

    @SneakyThrows
    public static long murmurHash3_64(BiAction<Hasher> fn) {
        Hasher hasher = Hashing.murmur3_128().newHasher();
        fn.invoke(hasher);
        return hasher.hash().asLong();
    }

    @SneakyThrows
    public static UUID murmurHash3_128(BiAction<Hasher> fn) {
        Hasher hasher = Hashing.murmur3_128().newHasher();
        fn.invoke(hasher);
        return SUID.newUUID(hasher.hash().asBytes());
    }

    public static BigInteger hashUnsigned64(Object... args) {
        return hashUnsigned64(Serializer.DEFAULT.serializeToBytes(args));
    }

    public static BigInteger hashUnsigned64(byte[] buf) {
        return hashUnsigned64(buf, 0, buf.length);
    }

    //UnsignedLong.fromLongBits
    public static BigInteger hashUnsigned64(byte[] buf, int offset, int len) {
        long value = hash64(buf, offset, len);
        BigInteger bigInt = BigInteger.valueOf(value & 9223372036854775807L);
        if (value < 0L) {
            bigInt = bigInt.setBit(63);
        }
        return bigInt;
    }

    public static long hash64(Object... args) {
        return hash64(Serializer.DEFAULT.serializeToBytes(args));
    }

    public static long hash64(byte[] buf) {
        return hash64(buf, 0, buf.length);
    }

    public static long hash64(byte[] buf, int offset, int len) {
        return CrcModel.CRC64_ECMA_182.getCRC(buf, offset, len).getCrc();
    }

    public static UUID combId() {
        return combId(System.nanoTime(), null);
    }

    public static UUID combId(long timestamp, String key) {
        return combId(timestamp, key, false);
    }

    //http://www.codeproject.com/Articles/388157/GUIDs-as-fast-primary-keys-under-multiple-database
    public static UUID combId(long timestamp, String key, boolean sequentialAtEnd) {
        long id;
        if (key != null) {
            id = hash64(key.getBytes(StandardCharsets.UTF_8));
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
    public static String convertToBase64(byte[] data) {
        byte[] ret = Base64.getEncoder().encode(data);
        return new String(ret, StandardCharsets.UTF_8);
    }

    public static byte[] convertFromBase64(@NonNull String base64) {
        byte[] data = base64.getBytes(StandardCharsets.UTF_8);
        return Base64.getDecoder().decode(data);
    }

    public static <T extends Serializable> String serializeToBase64(T obj) {
        byte[] data = Serializer.DEFAULT.serializeToBytes(obj);
        return convertToBase64(data);
    }

    public static <T extends Serializable> T deserializeFromBase64(String base64) {
        byte[] data = convertFromBase64(base64);
        return Serializer.DEFAULT.deserialize(new MemoryStream(data, 0, data.length));
    }
    //endregion
}
