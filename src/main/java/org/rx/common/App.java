package org.rx.common;

import com.alibaba.fastjson.JSONObject;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import com.google.common.net.HttpHeaders;
import com.google.common.reflect.ClassPath;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.rx.annotation.ErrorCode;
import org.rx.beans.ShortUUID;
import org.rx.beans.Tuple;
import org.rx.cache.MemoryCache;
import org.rx.cache.WeakCache;
import org.rx.security.MD5Util;
import org.rx.beans.DateTime;
import org.rx.socks.http.HttpClient;
import org.rx.util.function.Action;
import org.rx.util.function.Func;
import org.rx.io.MemoryStream;
import org.rx.util.StringBuilder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Pattern;

import static org.rx.common.Contract.*;

@Slf4j
public class App {
    //region Nested
    public enum CacheContainerKind {
        ObjectCache,
        WeakCache,
        ThreadStatic,
        ServletRequest
    }

    @RequiredArgsConstructor
    private static class ConvertItem {
        public final Class baseFromType;
        public final Class toType;
        public final BiFunction<Object, Class, Object> converter;
    }
    //endregion

    //region Fields
    public static final int MaxSize = Integer.MAX_VALUE - 8;
    public static final int TimeoutInfinite = -1;
    private static final ThreadLocal<Map> threadStatic;
    private static final NQuery<Class<?>> supportTypes;
    private static final List<ConvertItem> typeConverter;

    public static Map threadMap() {
        return threadStatic.get();
    }

    static {
        System.setProperty("bootstrapPath", getBootstrapPath());
        threadStatic = ThreadLocal.withInitial(HashMap::new);
        supportTypes = NQuery.of(String.class, Boolean.class, Byte.class, Short.class, Integer.class, Long.class,
                Float.class, Double.class, Enum.class, Date.class, UUID.class, BigDecimal.class);
        typeConverter = Collections.synchronizedList(new ArrayList<>(2));
    }
    //endregion

    //region Basic
    public static boolean windowsOS() {
        return isNull(System.getProperty("os.name"), "").toLowerCase().contains("windows");
    }

    public static List<String> execShell(String workspace, String... shellStrings) {
        List<String> resultList = new ArrayList<>();
        java.lang.StringBuilder msg = new java.lang.StringBuilder();
        File dir = null;
        if (workspace != null) {
            msg.append(String.format("execShell workspace=%s\n", workspace));
            dir = new File(workspace);
        }
        for (String shellString : shellStrings) {
            msg.append(String.format("pre-execShell %s", shellString));
            java.lang.StringBuilder result = new java.lang.StringBuilder();
            try {
                Process process;
                if (windowsOS()) {
                    process = Runtime.getRuntime().exec(shellString, null, dir);
                } else {
                    process = Runtime.getRuntime().exec(new String[]{"/bin/sh", "-c", shellString}, null, dir);
                }
                try (LineNumberReader input = new LineNumberReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = input.readLine()) != null) {
                        result.append(line).append("\n");
                    }
                }
            } catch (Exception e) {
                log.error("execShell", e);
                result.append("ERROR: " + e.getMessage()).append("\n");
            }
            msg.append(String.format("\npost-execShell %s\n\n", result));
            if (result.length() == 0) {
                result.append("NULL");
            }
            resultList.add(result.toString());
        }
        log.info(msg.toString());
        return resultList;
    }

    public static String getBootstrapPath() {
        String p = App.class.getClassLoader().getResource("").getFile();
        System.out.println("bootstrapPath:" + p);
        if (windowsOS()) {
            if (p.startsWith("file:/")) {
                p = p.substring(6);
            } else {
                p = p.substring(1);
            }
        }
        return p;
    }

    public static HttpServletRequest getCurrentRequest() {
        ServletRequestAttributes ra = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return ra == null ? null : ra.getRequest();
    }

    public static <T, TR> TR retry(int retryCount, Function<T, TR> func, T state) {
        return retry(retryCount, func, state, TimeoutInfinite, false);
    }

    @SneakyThrows
    public static <T, TR> TR retry(int retryCount, Function<T, TR> func, T state, long sleepMillis,
                                   boolean sleepFirst) {
        require(retryCount, retryCount > 0);
        require(func);

        SystemException lastEx = null;
        int i = 1;
        while (i <= retryCount) {
            if (sleepMillis > -1 && sleepFirst) {
                Thread.sleep(sleepMillis);
            }
            try {
                return func.apply(state);
            } catch (Exception ex) {
                if (i == retryCount) {
                    lastEx = SystemException.wrap(ex);
                }
            }
            if (sleepMillis > -1 && !sleepFirst) {
                Thread.sleep(sleepMillis);
            }
            i++;
        }
        throw lastEx;
    }

    public static void catchCall(Action action) {
        require(action);

        try {
            action.invoke();
        } catch (Exception ex) {
            log.warn("CatchCall", ex);
        }
    }

    public static <T> T catchCall(Func<T> action) {
        require(action);

        try {
            return action.invoke();
        } catch (Exception ex) {
            log.warn("CatchCall", ex);
        }
        return null;
    }

    public static ClassLoader getClassLoader() {
        return isNull(Thread.currentThread().getContextClassLoader(), App.class.getClassLoader());
    }

    public static <T> Class<T> loadClass(String className, boolean initialize) {
        return loadClass(className, initialize, true);
    }

    public static Class loadClass(String className, boolean initialize, boolean throwOnEmpty) {
        try {
            return Class.forName(className, initialize, getClassLoader());
        } catch (ClassNotFoundException e) {
            if (!throwOnEmpty) {
                return null;
            }
            throw SystemException.wrap(e);
        }
    }

    public static <T> T newInstance(Class<T> type) {
        Object[] args = null;
        return newInstance(type, args);
    }

    public static <T> T newInstance(Class<T> type, Object... args) {
        require(type);
        if (args == null) {
            args = Contract.EmptyArray;
        }

        try {
            for (Constructor<?> constructor : type.getConstructors()) {
                Class[] paramTypes = constructor.getParameterTypes();
                if (paramTypes.length != args.length) {
                    continue;
                }
                boolean ok = true;
                for (int i = 0; i < paramTypes.length; i++) {
                    if (!paramTypes[i].isInstance(args[i])) {
                        ok = false;
                        break;
                    }
                }
                if (!ok) {
                    continue;
                }
                constructor.setAccessible(true);
                return (T) constructor.newInstance(args);
            }
        } catch (ReflectiveOperationException ex) {
            throw SystemException.wrap(ex);
        }
        throw new SystemException("Parameters error");
    }

    @SneakyThrows
    public static List<Class> getClassesFromPackage(String packageDirName, ClassLoader classloader) {
        require(packageDirName, classloader);

        ImmutableSet<ClassPath.ClassInfo> classes = ClassPath.from(classloader).getTopLevelClasses(packageDirName);
        return NQuery.of(classes).select(p -> (Class) p.load()).toList();
    }

    @ErrorCode(value = "argError", messageKeys = {"$type"})
    public static <T> List<T> asList(Object arrayOrIterable) {
        require(arrayOrIterable);

        Class type = arrayOrIterable.getClass();
        if (type.isArray()) {
            int length = Array.getLength(arrayOrIterable);
            List<T> list = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                Object item = Array.get(arrayOrIterable, i);
                list.add((T) item);
            }
            return list;
        }

        Iterable iterable;
        if ((iterable = as(arrayOrIterable, Iterable.class)) != null) {
            List<T> list = new ArrayList<>();
            iterable.forEach(p -> list.add((T) p));
            return list;
        }

        throw new SystemException(values(type.getSimpleName()), "argError");
    }

    public static <T> T getOrStore(String key, Function<String, T> supplier) {
        return getOrStore(key, supplier, CacheContainerKind.WeakCache);
    }

    public static <T> T getOrStore(String key, Function<String, T> supplier, CacheContainerKind containerKind) {
        require(key, supplier, containerKind);

        String k = cacheKey(key);
        Object v;
        switch (containerKind) {
            case ThreadStatic:
                v = threadMap().computeIfAbsent(k, supplier);
                break;
            case ServletRequest:
                HttpServletRequest request = getCurrentRequest();
                if (request == null) {
                    return supplier.apply(key);
                }
                v = request.getAttribute(k);
                if (v == null) {
                    request.setAttribute(k, v = supplier.apply(k));
                }
                break;
            case ObjectCache:
                v = MemoryCache.getOrStore(key, (Function<String, Object>) supplier);
                break;
            default:
                v = WeakCache.getOrStore(key, (Function<String, Object>) supplier);
                break;
        }
        return (T) v;
    }

    public static String cacheKey(String key) {
        require(key);

        if (key.length() <= 32) {
            return key;
        }
        return MD5Util.md5Hex(key);
    }

    public static UUID hash(String key) {
        require(key);

        byte[] guidBytes = MD5Util.md5(key);
        return newUUID(guidBytes);
    }

    public static UUID newComb(boolean sequentialAtEnd) {
        return newComb(null, null);
    }

    public static UUID newComb(String key, Date now) {
        return newComb(key, now, false);
    }

    //http://www.codeproject.com/Articles/388157/GUIDs-as-fast-primary-keys-under-multiple-database
    public static UUID newComb(String key, Date date, boolean sequentialAtEnd) {
        byte[] guidBytes, msecsBytes;
        if (key != null) {
            guidBytes = MD5Util.md5(key);
        } else {
            guidBytes = new byte[16];
            ThreadLocalRandom.current().nextBytes(guidBytes);
        }
        if (date != null) {
            msecsBytes = ByteBuffer.allocate(8).putLong(date.getTime() - DateTime.BaseDate.getTime()).array();
        } else {
            msecsBytes = ByteBuffer.allocate(8).putLong(System.nanoTime() - DateTime.BaseDate.getTime()).array();
        }
        int copyCount = 6, copyOffset = msecsBytes.length - copyCount;
        if (sequentialAtEnd) {
            System.arraycopy(msecsBytes, copyOffset, guidBytes, guidBytes.length - copyCount, copyCount);
        } else {
            System.arraycopy(msecsBytes, copyOffset, guidBytes, 0, copyCount);
        }
        return newUUID(guidBytes);
    }

    private static UUID newUUID(byte[] guidBytes) {
        long msb = 0, lsb = 0;
        for (int i = 0; i < 8; i++) {
            msb = (msb << 8) | (guidBytes[i] & 0xff);
        }
        for (int i = 8; i < 16; i++) {
            lsb = (lsb << 8) | (guidBytes[i] & 0xff);
        }
        return new UUID(msb, lsb);
    }

    /**
     * 把 UUID 转为22位长字符串
     */
    public static String toShorterUUID(UUID uuid) {
        require(uuid);

        return new ShortUUID.Builder().build(uuid).toString();
    }

    /**
     * 把22位长字符串转为 UUID
     */
    public static UUID fromShorterUUID(String shorterUUID) {
        require(shorterUUID);
        require(shorterUUID, shorterUUID.length() == 22);

        return UUID.fromString(new ShortUUID.Builder().decode(shorterUUID));
    }

    public static String randomValue(int maxValue) {
        Integer int2 = maxValue;
        return String.format("%0" + int2.toString().length() + "d", ThreadLocalRandom.current().nextInt(maxValue));
    }

    public static <T> T readSetting(String key) {
        return readSetting(key, null);
    }

    public static <T> T readSetting(String key, Class<T> type) {
        return readSetting(key, type, "application.yml");
    }

    @ErrorCode(value = "keyError", messageKeys = {"$key", "$file"})
    @ErrorCode(value = "partialKeyError", messageKeys = {"$key", "$file"})
    public static <T> T readSetting(String key, Class<T> type, String yamlFile) {
        require(key, yamlFile);

        Function<Object, T> func = p -> {
            if (type == null) {
                return (T) p;
            }
            Map<String, Object> map = as(p, Map.class);
            if (map != null) {
                return new JSONObject(map).toJavaObject(type);
            }
            return changeType(p, type);
        };
        Map<String, Object> settings = loadYaml(yamlFile);
        Object val;
//        if ((val = settings.get(key)) != null) {
//            return func.apply(val);
//        }

        StringBuilder kBuf = new StringBuilder();
        String d = ".";
        String[] splits = split(key, d);
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
                throw new SystemException(values(k, yamlFile), "partialKeyError");
            }
            kBuf.setLength(0);
        }

        return null;
        //        throw new SystemException(values(key, yamlFile), "keyError");
    }

    @SneakyThrows
    public static Map<String, Object> loadYaml(String yamlFile) {
        require(yamlFile);

        File file = new File(yamlFile);
        Map<String, Object> result = new HashMap<>();
        Yaml yaml = new Yaml(new SafeConstructor());
        for (Object data : yaml.loadAll(file.exists() ? new FileInputStream(file) : getClassLoader().getResourceAsStream(yamlFile))) {
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

    public static <T> T loadYaml(InputStream yamlStream, Class<T> beanType) {
        require(yamlStream, beanType);

        Yaml yaml = new Yaml();
        return yaml.loadAs(yamlStream, beanType);
    }

    public static <T> String dumpYaml(T bean) {
        require(bean);

        Yaml yaml = new Yaml();
        return yaml.dumpAsMap(bean);
    }

    public static void createDirectory(String dirPath) {
        File dir = new File(dirPath);
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    @SneakyThrows
    public static DirectoryStream<Path> fileStream(Path dir) {
        return Files.newDirectoryStream(dir, Files::isRegularFile);
    }
    //endregion

    //region Check
    public static boolean isNullOrEmpty(String input) {
        return input == null || input.length() == 0 || "null".equals(input);
    }

    public static boolean isNullOrWhiteSpace(String input) {
        return isNullOrEmpty(input) || input.trim().length() == 0;
    }

    public static boolean equals(String s1, String s2, boolean ignoreCase) {
        if (s1 == null) {
            if (s2 == null) {
                return true;
            }
            return false;
        }
        return ignoreCase ? s1.equals(s2) : s1.equalsIgnoreCase(s2);
    }

    public static String[] split(String str, String delimiter) {
        return split(str, delimiter, null);
    }

    @ErrorCode(value = "lengthError", messageKeys = {"$len"})
    public static String[] split(String str, String delimiter, Integer length) {
        String[] result;
        if (isNullOrEmpty(str)) {
            result = new String[0];
        } else {
            result = str.split(Pattern.quote(delimiter));
        }
        if (length != null && length != result.length) {
            throw new SystemException(values(length), "lengthError");
        }
        return result;
    }

    public static String toTitleCase(String s) {
        return StringUtils.capitalize(s);
    }

    public static String filterPrivacy(String val) {
        if (isNullOrEmpty(val)) {
            return "";
        }

        val = val.trim();
        int len = val.length(), left, right;
        switch (len) {
            case 11:
                left = 3;
                right = 4;
                break;
            case 18:
                left = 4;
                right = 6;
                break;
            default:
                if (len < 3) {
                    left = 1;
                    right = 0;
                } else {
                    left = right = len / 3;
                }
                break;
        }
        String x = Strings.repeat("*", len - left - right);
        return val.substring(0, left) + x + val.substring(left + x.length());
    }
    //endregion

    //region Converter
    public static <T> T convert(Object val, Class<T> toType) {
        return tryConvert(val, toType).right;
    }

    public static <T> Tuple<Boolean, T> tryConvert(Object val, Class<T> toType) {
        return tryConvert(val, toType, null);
    }

    public static <T> Tuple<Boolean, T> tryConvert(Object val, Class<T> toType, T defaultVal) {
        require(toType);

        try {
            return Tuple.of(true, changeType(val, toType));
        } catch (Exception ex) {
            return Tuple.of(false, defaultVal);
        }
    }

    /**
     * @param baseFromType
     * @param toType
     * @param converter    BiFunction<TFromValue, TToType, TToValue>
     */
    public synchronized static <TFromValue> void registerConverter(Class<TFromValue> baseFromType, Class toType, BiFunction<TFromValue, Class, Object> converter) {
        require(baseFromType, toType, converter);

        typeConverter.add(0, new ConvertItem(baseFromType, toType, (BiFunction) converter));
        if (!supportTypes.contains(baseFromType)) {
            supportTypes.asCollection().add(baseFromType);
        }
    }

    private static BiFunction<Object, Class, Object> getConverter(Object fromValue, Class toType) {
        for (ConvertItem convertItem : NQuery.of(typeConverter).toList()) {
            if (convertItem.baseFromType.isInstance(fromValue) && convertItem.toType.isAssignableFrom(toType)) {
                return convertItem.converter;
            }
        }
        return null;
    }

    @ErrorCode(value = "notSupported", messageKeys = {"$fType", "$tType"})
    @ErrorCode(value = "enumError", messageKeys = {"$name", "$names", "$eType"})
    @ErrorCode(cause = NoSuchMethodException.class, messageKeys = {"$type"})
    @ErrorCode(cause = ReflectiveOperationException.class, messageKeys = {"$fType", "$tType", "$val"})
    public static <T> T changeType(Object value, Class<T> toType) {
        require(toType);

        if (value == null) {
            if (toType.isPrimitive()) {
                if (boolean.class.equals(toType)) {
                    value = false;
                } else {
                    value = 0;
                }
            } else {
                return null;
            }
        }
        if (toType.isInstance(value)) {
            return (T) value;
        }
        Class<?> strType = supportTypes.first();
        if (toType.equals(strType)) {
            return (T) value.toString();
        }
        final Class<?> fromType = value.getClass();
        if (!(supportTypes.any(p -> p.equals(fromType)))) {
            throw new SystemException(values(fromType, toType), "notSupported");
        }
        BiFunction<Object, Class, Object> converter = getConverter(value, toType);
        if (converter != null) {
            return (T) converter.apply(value, toType);
        }

        String val = value.toString();
        if (toType.equals(UUID.class)) {
            value = UUID.fromString(val);
        } else if (toType.equals(BigDecimal.class)) {
            value = new BigDecimal(val);
        } else if (toType.isEnum()) {
            NQuery<String> q = NQuery.of(toType.getEnumConstants()).select(p -> ((Enum) p).name());
            value = q.where(p -> p.equals(val)).singleOrDefault();
            if (value == null) {
                throw new SystemException(values(val, String.join(",", q), toType.getSimpleName()), "enumError");
            }
        } else {
            final String of = "valueOf";
            try {
                Method m;
                if (Date.class.isAssignableFrom(toType)) {
                    m = DateTime.class.getDeclaredMethod(of, strType);
                } else {
                    toType = checkType(toType);
                    m = toType.getDeclaredMethod(of, strType);
                }
                value = m.invoke(null, val);
            } catch (NoSuchMethodException ex) {
                throw new SystemException(values(toType), ex);
            } catch (ReflectiveOperationException ex) {
                throw new SystemException(values(fromType, toType, val), ex);
            }
        }
        return (T) value;
    }

    @ErrorCode(messageKeys = {"$type"})
    private static Class checkType(Class type) {
        if (!type.isPrimitive()) {
            return type;
        }

        String pName = type.equals(int.class) ? "Integer" : type.getName();
        String newName = "java.lang." + pName.substring(0, 1).toUpperCase() + pName.substring(1, pName.length());
        try {
            return Class.forName(newName);
        } catch (ClassNotFoundException ex) {
            throw new SystemException(values(newName), ex);
        }
    }

    public static boolean isBase64String(String base64String) {
        if (isNullOrEmpty(base64String)) {
            return false;
        }

        return org.apache.commons.codec.binary.Base64.isBase64(base64String);
    }

    @SneakyThrows
    public static String convertToBase64String(byte[] data) {
        require(data);

        byte[] ret = Base64.getEncoder().encode(data);
        return new String(ret, Contract.Utf8);
    }

    @SneakyThrows
    public static byte[] convertFromBase64String(String base64) {
        require(base64);

        byte[] data = base64.getBytes(Contract.Utf8);
        return Base64.getDecoder().decode(data);
    }

    public static String serializeToBase64(Object obj) {
        byte[] data = serialize(obj);
        return convertToBase64String(data);
    }

    @SneakyThrows
    public static byte[] serialize(Object obj) {
        require(obj);

        try (MemoryStream stream = new MemoryStream();
             ObjectOutputStream out = new ObjectOutputStream(stream.getWriter())) {
            out.writeObject(obj);
            return stream.toArray();
        }
    }

    public static Object deserializeFromBase64(String base64) {
        byte[] data = convertFromBase64String(base64);
        return deserialize(data);
    }

    @SneakyThrows
    public static Object deserialize(byte[] data) {
        require(data);

        try (MemoryStream stream = new MemoryStream(data, 0, data.length);
             ObjectInputStream in = new ObjectInputStream(stream.getReader())) {
            return in.readObject();
        }
    }

    public static <T> T deepClone(T obj) {
        byte[] data = serialize(obj);
        return (T) deserialize(data);
    }
    //endregion

    //region Servlet
    public static String getRequestIp(HttpServletRequest request) {
        if (request == null) {
            return "0.0.0.0";
        }

        String ip = request.getHeader(HttpHeaders.X_FORWARDED_FOR);
        if (isNullOrEmpty(ip) || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (isNullOrEmpty(ip) || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (isNullOrEmpty(ip) || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("HTTP_CLIENT_IP");
        }
        if (isNullOrEmpty(ip) || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("HTTP_X_FORWARDED_FOR");
        }
        if (isNullOrEmpty(ip) || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("x-real-ip");
        }
        if (isNullOrEmpty(ip) || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        String[] ips = ip.split(",");
        if (ips.length > 1) {
            ip = ips[0];
        }
        return ip;
    }

    @SneakyThrows
    public static void downloadFile(HttpServletResponse response, String filePath) {
        require(response, filePath);
        switch (filePath) {
            case "info":
            case "error":
                filePath = String.format("%s/logs/%s", System.getProperty("catalina.home"), filePath);
        }

        File file = new File(filePath);
        response.setCharacterEncoding(Contract.Utf8);
        response.setContentType("application/octet-stream");
        response.setContentLength((int) file.length());
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, String.format("attachment; filename=\"%s\"", file.getName()));
        try (FileInputStream in = new FileInputStream(file)) {
            OutputStream out = response.getOutputStream();
            byte[] buffer = new byte[4096];
            int length;
            while ((length = in.read(buffer)) > 0) {
                out.write(buffer, 0, length);
            }
            out.flush();
        }
    }

    public static String getCookie(String name) {
        require(name);

        HttpServletRequest servletRequest = App.getCurrentRequest();
        if (servletRequest == null) {
            throw new InvalidOperationException("上下环境无ServletRequest");
        }
        try {
            if (ArrayUtils.isEmpty(servletRequest.getCookies())) {
                return null;
            }
            return NQuery.of(servletRequest.getCookies()).where(p -> p.getName().equals(name)).select(p -> HttpClient.decodeUrl(p.getValue())).firstOrDefault();
        } catch (Exception e) {
            log.warn("getCookie", e);
            return null;
        }
    }

    public static void setCookie(HttpServletResponse response, String name, String value) {
        setCookie(response, name, value);
    }

    public static void setCookie(HttpServletResponse response, String name, String value, Date expire) {
        require(response, name);

        Cookie cookie = new Cookie(name, HttpClient.encodeUrl(value));
        cookie.setPath("/");
        cookie.setSecure(true);
        cookie.setHttpOnly(true);
        if (expire != null) {
            cookie.setMaxAge((int) new DateTime(expire).subtract(DateTime.now()).getTotalSeconds());
        }
        response.addCookie(cookie);
    }

    public static void deleteCookie(HttpServletResponse response, String name) {
        require(response, name);

        Cookie cookie = new Cookie(name, null);
        cookie.setPath("/");
        cookie.setSecure(true);
        cookie.setHttpOnly(true);
        cookie.setMaxAge(-1);
        response.addCookie(cookie);
    }
    //endregion
}
