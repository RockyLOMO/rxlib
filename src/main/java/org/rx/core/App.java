package org.rx.core;

import com.google.common.net.HttpHeaders;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.SystemUtils;
import org.rx.annotation.ErrorCode;
import org.rx.beans.AppConfig;
import org.rx.beans.ShortUUID;
import org.rx.beans.Tuple;
import org.rx.security.MD5Util;
import org.rx.beans.DateTime;
import org.rx.socks.http.HttpClient;
import org.rx.io.MemoryStream;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiFunction;
import java.util.function.Function;

import static org.rx.core.Contract.*;

@Slf4j
public class App extends SystemUtils {
    //region Nested
    @RequiredArgsConstructor
    private static class ConvertItem {
        public final Class baseFromType;
        public final Class toType;
        public final BiFunction<Object, Class, Object> converter;
    }
    //endregion

    //region Fields
    public static final AppConfig Config;
    private static final NQuery<Class<?>> supportTypes;
    private static final List<ConvertItem> typeConverter;

    static {
        System.setProperty("bootstrapPath", getBootstrapPath());
        Config = isNull(readSetting("app", AppConfig.class), new AppConfig());
        if (Config.getBufferSize() <= 0) {
            Config.setBufferSize(512);
        }
        Contract.init();
        supportTypes = NQuery.of(String.class, Boolean.class, Byte.class, Short.class, Integer.class, Long.class,
                Float.class, Double.class, Enum.class, Date.class, UUID.class, BigDecimal.class);
        typeConverter = new CopyOnWriteArrayList<>();
    }
    //endregion

    //region Basic
    public static String getBootstrapPath() {
        String p = App.class.getClassLoader().getResource("").getFile();
        if (IS_OS_WINDOWS) {
            if (p.startsWith("file:/")) {
                p = p.substring(6);
            } else {
                p = p.substring(1);
            }
        }
        System.out.println("bootstrapPath:" + p);
        return p;
    }

    public static List<String> execShell(String workspace, String... shellStrings) {
        List<String> resultList = new ArrayList<>();
        StringBuilder msg = new StringBuilder();
        File dir = null;
        if (workspace != null) {
            msg.append(String.format("execShell workspace=%s\n", workspace));
            dir = new File(workspace);
        }
        for (String shellString : shellStrings) {
            msg.append(String.format("pre-execShell %s", shellString));
            StringBuilder result = new StringBuilder();
            try {
                Process process;
                if (IS_OS_WINDOWS) {
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
            if (result.getLength() == 0) {
                result.append("NULL");
            }
            resultList.add(result.toString());
        }
        log.info(msg.toString());
        return resultList;
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

    public static <T> T readSetting(String key) {
        return readSetting(key, null);
    }

    public static <T> T readSetting(String key, Class<T> type) {
        return readSetting(key, type, loadYaml("application.yml"));
    }

    public static <T> T readSetting(String key, Class<T> type, Map<String, Object> settings) {
        return readSetting(key, type, settings, false);
    }

    @ErrorCode(value = "keyError", messageKeys = {"$key", "$type"})
    @ErrorCode(value = "partialKeyError", messageKeys = {"$key", "$type"})
    public static <T> T readSetting(String key, Class<T> type, Map<String, Object> settings, boolean throwOnEmpty) {
        require(key, settings);

        Function<Object, T> func = p -> {
            if (type == null) {
                return (T) p;
            }
            Map<String, Object> map = as(p, Map.class);
            if (map != null) {
                return fromJsonAsObject(map, type);
            }
            return changeType(p, type);
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
                throw new SystemException(values(k, type), "partialKeyError");
            }
            kBuf.setLength(0);
        }

        if (throwOnEmpty) {
            throw new SystemException(values(key, type), "keyError");
        }
        return null;
    }

    public static Map<String, Object> loadYaml(String... yamlFile) {
        require((Object) yamlFile);

        return MemoryCache.getOrStore(String.format("loadYaml-%s", toJsonString(yamlFile)), k -> {
            Map<String, Object> result = new HashMap<>();
            Yaml yaml = new Yaml(new SafeConstructor());
            for (String yf : yamlFile) {
                File file = new File(yf);
                for (Object data : yaml.loadAll(file.exists() ? new FileInputStream(file) : getClassLoader().getResourceAsStream(yf))) {
                    Map<String, Object> one = (Map<String, Object>) data;
                    fillDeep(one, result);
                }
            }
            return result;
        });
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

    //region Class
    @ErrorCode(messageKeys = {"$name", "$type"})
    public static InputStream getResource(Class owner, String name) {
        InputStream resource = owner.getResourceAsStream(name);
        if (resource == null) {
            throw new SystemException(values(owner, name));
        }
        return resource;
    }

    /**
     * ClassLoader.getSystemClassLoader()
     *
     * @return
     */
    public static ClassLoader getClassLoader() {
        return isNull(Thread.currentThread().getContextClassLoader(), App.class.getClassLoader());
    }

    public static <T> Class<T> loadClass(String className, boolean initialize) {
        return loadClass(className, initialize, true);
    }

    //ClassPath.from(classloader).getTopLevelClasses(packageDirName)
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

    public synchronized static <TFromValue> void registerConverter(Class<TFromValue> baseFromType, Class toType, BiFunction<TFromValue, Class, Object> converter) {
        require(baseFromType, toType, converter);

        typeConverter.add(0, new ConvertItem(baseFromType, toType, (BiFunction) converter));
        if (!supportTypes.contains(baseFromType)) {
            supportTypes.asCollection().add(baseFromType);
        }
    }

    private static BiFunction<Object, Class, Object> getConverter(Object fromValue, Class toType) {
        for (ConvertItem convertItem : NQuery.of(typeConverter).toList()) {
            if (Reflects.isInstance(fromValue, convertItem.baseFromType) && convertItem.toType.isAssignableFrom(toType)) {
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
        if (Reflects.isInstance(value, toType)) {
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
        if (Strings.isNullOrEmpty(base64String)) {
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
    public static HttpServletRequest getCurrentRequest() {
        ServletRequestAttributes ra = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return ra == null ? null : ra.getRequest();
    }

    public static String getRequestIp(HttpServletRequest request) {
        if (request == null) {
            return "0.0.0.0";
        }

        String ip = request.getHeader(HttpHeaders.X_FORWARDED_FOR);
        if (Strings.isNullOrEmpty(ip) || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (Strings.isNullOrEmpty(ip) || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (Strings.isNullOrEmpty(ip) || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("HTTP_CLIENT_IP");
        }
        if (Strings.isNullOrEmpty(ip) || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("HTTP_X_FORWARDED_FOR");
        }
        if (Strings.isNullOrEmpty(ip) || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("x-real-ip");
        }
        if (Strings.isNullOrEmpty(ip) || "unknown".equalsIgnoreCase(ip)) {
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

        HttpServletRequest servletRequest = getCurrentRequest();
        if (servletRequest == null) {
            throw new InvalidOperationException("上下环境无ServletRequest");
        }

        return catchCall(() -> {
            if (ArrayUtils.isEmpty(servletRequest.getCookies())) {
                return null;
            }
            return NQuery.of(servletRequest.getCookies()).where(p -> p.getName().equals(name)).select(p -> HttpClient.decodeUrl(p.getValue())).firstOrDefault();
        });
    }

    public static void setCookie(HttpServletResponse response, String name, String value) {
        setCookie(response, name, value);
    }

    public static void setCookie(HttpServletResponse response, String name, String value, Date expire) {
        require(response, name);

        Cookie cookie = new Cookie(name, HttpClient.encodeUrl(value));
        cookie.setPath("/");
//        cookie.setSecure(true);
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
//        cookie.setSecure(true);
        cookie.setHttpOnly(true);
        cookie.setMaxAge(-1);
        response.addCookie(cookie);
    }
    //endregion
}
