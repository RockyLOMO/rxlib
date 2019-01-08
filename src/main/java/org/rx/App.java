package org.rx;

import com.google.common.base.Strings;
import lombok.SneakyThrows;
import org.apache.commons.lang3.StringUtils;
import org.rx.bean.$;
import org.rx.bean.Tuple;
import org.rx.cache.WeakCache;
import org.rx.security.MD5Util;
import org.rx.bean.DateTime;
import org.rx.util.Action;
import org.rx.util.Func;
import org.rx.io.MemoryStream;
import org.rx.util.StringBuilder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

import static org.rx.bean.$.$;
import static org.rx.Contract.*;

public class App {
    //region Nested
    public enum CacheContainerKind {
        WeakCache,
        ThreadStatic,
        ServletRequest
    }
    //endregion

    //region Fields
    public static final int               TimeoutInfinite = -1;
    private static final String           base64Regex     = "^([A-Za-z0-9+/]{4})*([A-Za-z0-9+/]{4}|[A-Za-z0-9+/]{3}=|[A-Za-z0-9+/]{2}==)$";
    private static final ThreadLocal<Map> threadStatic;
    private static final NQuery<Class<?>> supportTypes;

    static {
        threadStatic = ThreadLocal.withInitial(HashMap::new);
        supportTypes = NQuery.of(String.class, Boolean.class, Byte.class, Short.class, Integer.class, Long.class,
                Float.class, Double.class, Enum.class, Date.class, UUID.class, BigDecimal.class);
    }
    //endregion

    //region Basic
    public static boolean windowsOS() {
        return isNull(System.getProperty("os.name"), "").toLowerCase().contains("windows");
    }

    public static String getBootstrapPath() {
        String p = App.class.getClassLoader().getResource("").getFile();
        if (windowsOS()) {
            p = p.substring(1);
        }
        return p;
    }

    public static HttpServletRequest getCurrentRequest() {
        ServletRequestAttributes ra = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return ra == null ? null : ra.getRequest();
    }

    public static <T, TR> TR retry(Function<T, TR> func, T state, int retryCount) {
        require(func);
        require(retryCount, retryCount > 0);

        SystemException lastEx = null;
        int i = 1;
        while (i <= retryCount) {
            try {
                return func.apply(state);
            } catch (Exception ex) {
                if (i == retryCount) {
                    lastEx = SystemException.wrap(ex);
                }
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
            Logger.info("CatchCall %s", ex.getMessage());
        }
    }

    public static <T> T catchCall(Func<T> action) {
        require(action);

        try {
            return action.invoke();
        } catch (Exception ex) {
            Logger.info("CatchCall %s", ex.getMessage());
        }
        return null;
    }

    public static <T extends Exception> void catchCall(Action action, Function<Exception, T> exFunc) {
        require(action);

        try {
            action.invoke();
        } catch (Exception ex) {
            throw new SystemException(isNull(exFunc != null ? exFunc.apply(ex) : null, ex));
        }
    }

    public static <T, TE extends Exception> T catchCall(Func<T> action, Function<Exception, TE> exFunc) {
        require(action);

        try {
            return action.invoke();
        } catch (Exception ex) {
            throw new SystemException(isNull(exFunc != null ? exFunc.apply(ex) : null, ex));
        }
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

    public static List<Class> getClassesFromPackage(String packageDirName) {
        return getClassesFromPackage(packageDirName, false);
    }

    @SneakyThrows
    public static List<Class> getClassesFromPackage(String packageDirName, boolean initClass) {
        require(packageDirName);
        packageDirName = packageDirName.replace('.', '/');

        final String flag = ".class";
        List<Class> classes = new ArrayList<>();
        final Consumer<String> loadFunc = n -> {
            Class type = loadClass(n, initClass, false);
            if (type == null) {
                return;
            }
            classes.add(type);
        };
        Enumeration<URL> dirs = getClassLoader().getResources(packageDirName);
        while (dirs.hasMoreElements()) {
            URL url = dirs.nextElement();
            switch (url.getProtocol()) {
                case "file":
                    $<BiConsumer<String, String>> callee = $();
                    callee.$ = (packageName, filePath) -> {
                        File dir = new File(filePath);
                        if (!dir.exists() || !dir.isDirectory()) {
                            return;
                        }

                        for (File file : isNull(dir.listFiles(p -> p.isDirectory() || p.getName().endsWith(flag)),
                                new File[0])) {
                            if (file.isDirectory()) {
                                callee.$.accept(packageName + "." + file.getName(), file.getAbsolutePath());
                                continue;
                            }

                            String className = packageName + "."
                                    + file.getName().substring(0, file.getName().length() - flag.length());
                            loadFunc.accept(className);
                        }
                    };
                    String filePath = URLDecoder.decode(url.getFile(), "UTF-8");
                    callee.$.accept(packageDirName, filePath);
                    break;
                case "jar":
                    JarFile jar = ((JarURLConnection) url.openConnection()).getJarFile();
                    Enumeration<JarEntry> tor = jar.entries();
                    while (tor.hasMoreElements()) {
                        JarEntry entry = tor.nextElement();
                        String name = entry.getName();
                        if (name.charAt(0) == '/') {
                            name = name.substring(1);
                        }
                        if (!(name.startsWith(packageDirName) && name.endsWith(flag))) {
                            continue;
                        }

                        String className = entry.getName().replace('/', '.');
                        className = className.substring(0, className.length() - flag.length());
                        loadFunc.accept(className);
                    }
                    break;
                default:
                    System.out.println("getClassesFromPackage skip " + url.getProtocol());
                    break;
            }
        }
        return classes;
    }

    @ErrorCode(value = "argError", messageKeys = { "type" })
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
        return getOrStore(App.class, key, supplier);
    }

    public static <T> T getOrStore(Class caller, String key, Function<String, T> supplier) {
        return getOrStore(caller, key, supplier, CacheContainerKind.WeakCache);
    }

    public static <T> T getOrStore(Class caller, String key, Function<String, T> supplier,
                                   CacheContainerKind containerKind) {
        require(caller, key, supplier, containerKind);

        String k = cacheKey(caller.getName() + key);
        Object v;
        switch (containerKind) {
            case ThreadStatic:
                Map threadMap = threadStatic.get();
                v = threadMap.computeIfAbsent(k, supplier);
                break;
            case ServletRequest:
                HttpServletRequest request = getCurrentRequest();
                v = request.getAttribute(k);
                if (v == null) {
                    request.setAttribute(k, v = supplier.apply(k));
                }
                break;
            default:
                v = WeakCache.getOrStore(caller, key, (Function<String, Object>) supplier);
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

    public static String randomValue(int maxValue) {
        Integer int2 = maxValue;
        return String.format("%0" + int2.toString().length() + "d", ThreadLocalRandom.current().nextInt(maxValue));
    }

    public static Object readSetting(String key) {
        return readSetting(key, Contract.SettingsFile, false);
    }

    @ErrorCode(value = "keyError", messageKeys = { "$key", "$file" })
    @ErrorCode(value = "partialKeyError", messageKeys = { "$key", "$file" })
    public static Object readSetting(String key, String yamlFile, boolean throwOnEmpty) {
        Map<String, Object> settings = readSettings(yamlFile + ".yml");
        Object val;
        if ((val = settings.get(key)) != null) {
            return val;
        }

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
                return val;
            }
            if ((settings = as(val, Map.class)) == null) {
                throw new SystemException(values(k, yamlFile), "partialKeyError");
            }
            kBuf.setLength(0);
        }

        if (!throwOnEmpty) {
            return null;
        }
        throw new SystemException(values(key, yamlFile), "keyError");
    }

    public static Map<String, Object> readSettings(String yamlFile) {
        return readSettings(yamlFile, true);
    }

    @SneakyThrows
    public static Map<String, Object> readSettings(String yamlFile, boolean isResource) {
        Map<String, Object> result = null;
        Yaml yaml = new Yaml(new SafeConstructor());
        for (Object data : yaml
                .loadAll(isResource ? getClassLoader().getResourceAsStream(yamlFile) : new FileInputStream(yamlFile))) {
            Map<String, Object> map = (Map<String, Object>) data;
            if (result == null) {
                result = map;
                continue;
            }
            result.putAll(map);
        }
        if (result == null) {
            result = new HashMap<>();
        }
        return result;
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

    public static boolean isNullOrEmpty(Number input) {
        return input == null || input.equals(0);
    }

    public static <E> boolean isNullOrEmpty(E[] input) {
        return input == null || input.length == 0;
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

    public static String[] split(String s, String delimiter) {
        if (isNullOrEmpty(s)) {
            return new String[0];
        }

        return s.split(Pattern.quote(delimiter));
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

    @ErrorCode(value = "notSupported", messageKeys = { "$fType", "$tType" })
    @ErrorCode(value = "enumError", messageKeys = { "$name", "$names", "$eType" })
    @ErrorCode(cause = NoSuchMethodException.class, messageKeys = { "$type" })
    @ErrorCode(cause = ReflectiveOperationException.class, messageKeys = { "$fType", "$tType", "$val" })
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

    @ErrorCode(messageKeys = { "$type" })
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

    public static boolean isBase64String(String base64) {
        if (isNullOrEmpty(base64)) {
            return false;
        }

        return Pattern.compile(base64Regex).matcher(base64).find();
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

        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("HTTP_CLIENT_IP");
        }
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("HTTP_X_FORWARDED_FOR");
        }
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("x-real-ip");
        }
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
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
        response.setHeader("Content-Disposition", String.format("attachment; filename=\"%s\"", file.getName()));
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
    //endregion
}
