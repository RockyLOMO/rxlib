package org.rx.util;

import com.google.common.base.Strings;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.rx.common.DateTime;
import org.rx.common.Func;
import org.rx.common.NQuery;
import org.rx.common.Tuple;
import org.rx.security.MD5Util;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import java.io.*;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.nio.ByteBuffer;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static org.rx.common.Contract.require;

/**
 * Created by wangxiaoming on 2016/1/25.
 */
public class App {
    //region Fields
    public static final String                    CurrentPath = System.getProperty("user.dir");
    public static final String                    TmpDirPath  = String.format("%s%srx",
            System.getProperty("java.io.tmpdir"), File.separatorChar);
    public static final String                    Catalina    = System.getProperty("catalina.home"), UTF8 = "UTF-8";
    private static final Log                      log1        = LogFactory.getLog("helperInfo"),
            log2 = LogFactory.getLog("helperError");
    private static final NQuery<Class<?>>         SupportTypes;
    private static final NQuery<SimpleDateFormat> SupportDateFormats;
    private static final String                   base64Regex = "^([A-Za-z0-9+/]{4})*([A-Za-z0-9+/]{4}|[A-Za-z0-9+/]{3}=|[A-Za-z0-9+/]{2}==)$";

    static {
        SupportTypes = new NQuery<>(new Class<?>[] { String.class, Boolean.class, Byte.class, Short.class,
                Integer.class, Long.class, Float.class, Double.class, BigDecimal.class, UUID.class, Date.class, });
        SupportDateFormats = new NQuery<>(new SimpleDateFormat[] { new SimpleDateFormat("yyyy-MM-dd HH:mm:ss"),
                new SimpleDateFormat("yyyyMMddHHmmss") });
    }

    private static Random getRandom() {
        return ThreadLocalRandom.current();
    }
    //endregion

    //region Sys
    public static void logInfo(String format, Object... args) {
        String msg = args.length == 0 ? format : String.format(format, args);
        log1.info(msg + System.lineSeparator());
    }

    public static void logError(Exception ex, String format, Object... args) {
        String msg = args.length == 0 ? format : String.format(format, args);
        log2.error(String.format("%s%s %s", System.lineSeparator(), ex.getMessage(), msg), ex);
    }

    public static String randomString(int strLength) {
        Random rnd = getRandom();
        StringBuilder ret = new StringBuilder();
        for (int i = 0; i < strLength; i++) {
            boolean isChar = (rnd.nextInt(2) % 2 == 0);// 输出字母还是数字
            if (isChar) { // 字符串
                int choice = rnd.nextInt(2) % 2 == 0 ? 65 : 97; // 取得大写字母还是小写字母
                ret.append((char) (choice + rnd.nextInt(26)));
            } else { // 数字
                ret.append(Integer.toString(rnd.nextInt(10)));
            }
        }
        return ret.toString();
    }

    public static String randomValue(int maxValue) {
        Integer int2 = maxValue;
        return String.format("%0" + int2.toString().length() + "d", getRandom().nextInt(maxValue));
    }

    public static UUID hash(String key) {
        byte[] guidBytes = MD5Util.md5(key);
        return newUUID(guidBytes);
    }

    public static UUID newComb(boolean sequentialAtEnd) {
        byte[] guidBytes = new byte[16];
        getRandom().nextBytes(guidBytes);
        byte[] msecsBytes = ByteBuffer.allocate(8).putLong(System.nanoTime() - DateTime.BaseDate.getTime()).array();
        int copyCount = 6, copyOffset = msecsBytes.length - copyCount;
        if (sequentialAtEnd) {
            System.arraycopy(msecsBytes, copyOffset, guidBytes, guidBytes.length - copyCount, copyCount);
        } else {
            System.arraycopy(msecsBytes, copyOffset, guidBytes, 0, copyCount);
        }
        return newUUID(guidBytes);
    }

    public static UUID createComb(String key, Date now) {
        return createComb(key, now, false);
    }

    //http://www.codeproject.com/Articles/388157/GUIDs-as-fast-primary-keys-under-multiple-database
    public static UUID createComb(String key, Date now, boolean sequentialAtEnd) {
        byte[] guidBytes = MD5Util.md5(key);
        byte[] msecsBytes = ByteBuffer.allocate(8).putLong(now.getTime() - DateTime.BaseDate.getTime()).array();
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

    public static Map<String, String> readSettings(String propertiesFile) {
        Properties prop = new Properties();
        try {
            prop.load(new InputStreamReader(
                    App.class.getClassLoader().getResourceAsStream(propertiesFile + ".properties"), UTF8));
        } catch (Exception ex) {
            throw new RuntimeException("readSettings", ex);
        }

        Map<String, String> map = new HashMap<>();
        for (String key : prop.stringPropertyNames()) {
            String val = prop.getProperty(key);
            if (val == null) {
                val = "";
            }
            map.put(key, val);
        }
        return map;
    }
    //endregion

    //region Check
    public static boolean isNullOrEmpty(String obj) {
        return obj == null || obj.length() == 0 || "null".equals(obj);
    }

    public static boolean isNullOrWhiteSpace(String obj) {
        return isNullOrEmpty(obj) || obj.trim().length() == 0;
    }

    public static boolean isNullOrEmpty(Number num) {
        return num == null || num.equals(0);
    }

    public static <E> boolean isNullOrEmpty(E[] obj) {
        return obj == null || obj.length == 0;
    }

    public static <E> boolean isNullOrEmpty(Collection<E> obj) {
        return obj == null || obj.size() == 0;
    }

    public static <K, V> boolean isNullOrEmpty(Map<K, V> obj) {
        return obj == null || obj.size() == 0;
    }

    public static <T> boolean equals(T t1, T t2) {
        if (t1 == null) {
            if (t2 == null) {
                return true;
            }
            return false;
        }
        return t1.equals(t2);
    }

    public static boolean equals(String str1, String str2, boolean ignoreCase) {
        if (str1 == null) {
            if (str2 == null) {
                return true;
            }
            return false;
        }
        return ignoreCase ? str1.equals(str2) : str1.equalsIgnoreCase(str2);
    }

    public static <T> T As(Object obj, Class<T> type) {
        if (!type.isInstance(obj)) {
            return null;
        }
        return (T) obj;
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

    public static String[] split(String str, String delimiter) {
        return str.split(Pattern.quote(delimiter));
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

    //region IO
    public static final int  TimeoutInfinite   = -1;
    private static final int DefaultBufferSize = 1024;

    public static String readString(InputStream stream) {
        return readString(stream, UTF8);
    }

    public static String readString(InputStream stream, String charset) {
        require(stream, charset);

        StringBuilder result = new StringBuilder();
        try (DataInputStream reader = new DataInputStream(stream)) {
            byte[] buffer = new byte[DefaultBufferSize];
            int read;
            while ((read = reader.read(buffer)) > 0) {
                result.append(new String(buffer, 0, read, charset));
            }
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
        return result.toString();
    }

    public static void writeString(OutputStream stream, String value) {
        writeString(stream, value, UTF8);
    }

    public static void writeString(OutputStream stream, String value, String charset) {
        require(stream, charset);

        try (DataOutputStream writer = new DataOutputStream(stream)) {
            byte[] data = value.getBytes(charset);
            writer.write(data);
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    public static void createDirectory(String dirPath) {
        File dir = new File(dirPath);
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    public static InetSocketAddress parseSocketAddress(String sockAddr) {
        require(sockAddr);
        String[] arr = sockAddr.split(":");
        require(arr, p -> p.length == 2);

        return new InetSocketAddress(arr[0], Integer.parseInt(arr[1]));
    }

    public static void setHttpProxy(String sockAddr) {
        setHttpProxy(sockAddr, null, null, null);
    }

    public static void setHttpProxy(String sockAddr, List<String> nonProxyHosts, String userName, String password) {
        InetSocketAddress ipe = parseSocketAddress(sockAddr);
        Properties prop = System.getProperties();
        prop.setProperty("http.proxyHost", ipe.getAddress().getHostAddress());
        prop.setProperty("http.proxyPort", String.valueOf(ipe.getPort()));
        prop.setProperty("https.proxyHost", ipe.getAddress().getHostAddress());
        prop.setProperty("https.proxyPort", String.valueOf(ipe.getPort()));
        if (!isNullOrEmpty(nonProxyHosts)) {
            //如"localhost|192.168.0.*"
            prop.setProperty("http.nonProxyHosts", String.join("|", nonProxyHosts));
        }
        if (userName != null && password != null) {
            Authenticator.setDefault(new UserAuthenticator(userName, password));
        }
    }

    static class UserAuthenticator extends Authenticator {
        private String userName;
        private String password;

        public UserAuthenticator(String userName, String password) {
            this.userName = userName;
            this.password = password;
        }

        protected PasswordAuthentication getPasswordAuthentication() {
            return new PasswordAuthentication(userName, password.toCharArray());
        }
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
        if (val == null) {
            return Tuple.of(true, null);
        }
        require(toType);

        try {
            return Tuple.of(true, changeType(val, toType));
        } catch (Exception ex) {
            return Tuple.of(false, defaultVal);
        }
    }

    public static <T> T changeType(Object value, Class<T> toType) {
        require(toType);

        final Class<?> fromType;
        if (value == null || toType.isInstance(value) || toType.equals(fromType = value.getClass())) {
            return (T) value;
        }
        Class<?> strType = SupportTypes.first();
        if (toType.equals(strType)) {
            return (T) value.toString();
        }

        if (!(SupportTypes.any(new Func<Class<?>, Boolean>() {
            @Override
            public Boolean invoke(Class<?> arg) {
                return arg.equals(fromType);
            }
        }) || fromType.isEnum())) {
            throw new RuntimeException(String.format("不支持类型%s=>%s的转换", fromType, toType));
        }

        String val = value.toString();
        if (toType.equals(BigDecimal.class)) {
            value = new BigDecimal(val);
        } else if (toType.equals(UUID.class)) {
            value = UUID.fromString(val);
        } else if (toType.equals(Date.class) || toType.equals(DateTime.class)) {
            value = null;
            ParseException lastEx = null;
            for (SimpleDateFormat format : SupportDateFormats) {
                try {
                    value = new DateTime(format.parse(val));
                } catch (ParseException ex) {
                    lastEx = ex;
                }
            }
            if (value == null) {
                NQuery<String> q = SupportDateFormats.select(new Func<SimpleDateFormat, String>() {
                    @Override
                    public String invoke(SimpleDateFormat arg) {
                        return arg.toPattern();
                    }
                });
                throw new RuntimeException(String.format("仅支持 %s format的日期转换", String.join(",", q)), lastEx);
            }
        } else {
            toType = checkType(toType);
            try {
                Method m = toType.getDeclaredMethod("valueOf", strType);
                value = m.invoke(null, val);
            } catch (NoSuchMethodException ex) {
                throw new RuntimeException(String.format("未找到类型%s的valueOf方法", toType), ex);
            } catch (ReflectiveOperationException ex) {
                throw new RuntimeException(String.format("类型%s=>%s转换错误", fromType, toType), ex);
            }
        }
        return (T) value;
    }

    private static Class checkType(Class type) {
        if (!type.isPrimitive()) {
            return type;
        }

        String pName = type.equals(int.class) ? "Integer" : type.getName();
        String newName = "java.lang." + pName.substring(0, 1).toUpperCase() + pName.substring(1, pName.length());
        try {
            return Class.forName(newName);
        } catch (ClassNotFoundException ex) {
            throw new RuntimeException(String.format("checkType %s=>%s", type, newName), ex);
        }
    }

    public static boolean isBase64String(String base64) {
        if (isNullOrEmpty(base64)) {
            return false;
        }

        return Pattern.compile(base64Regex).matcher(base64).find();
    }

    public static String convertToBase64String(byte[] data) {
        require(data);

        byte[] ret = Base64.getEncoder().encode(data);
        try {
            return new String(ret, UTF8);
        } catch (UnsupportedEncodingException ex) {
            throw new RuntimeException("convertToBase64String", ex);
        }
    }

    public static byte[] convertFromBase64String(String base64) {
        require(base64);

        byte[] data;
        try {
            data = base64.getBytes(UTF8);
        } catch (UnsupportedEncodingException ex) {
            throw new RuntimeException("convertFromBase64String", ex);
        }
        return Base64.getDecoder().decode(data);
    }

    public static String serializeToBase64(Object obj) {
        byte[] data = serialize(obj);
        return convertToBase64String(data);
    }

    public static byte[] serialize(Object obj) {
        require(obj);

        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
                ObjectOutputStream out = new ObjectOutputStream(bos)) {
            out.writeObject(obj);
            return bos.toByteArray();
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    public static Object deserializeFromBase64(String base64) {
        byte[] data = convertFromBase64String(base64);
        return deserialize(data);
    }

    public static Object deserialize(byte[] data) {
        require(data);

        try (ByteArrayInputStream bis = new ByteArrayInputStream(data);
                ObjectInputStream in = new ObjectInputStream(bis)) {
            return in.readObject();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    public static <T> T deepClone(T obj) {
        byte[] data = serialize(obj);
        return (T) deserialize(data);
    }

    public static <T> String convertToXml(T obj) throws JAXBException {
        JAXBContext jaxbContext = JAXBContext.newInstance(obj.getClass());
        Marshaller marshaller = jaxbContext.createMarshaller();
        //marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true); // pretty
        //marshaller.setProperty(Marshaller.JAXB_ENCODING, "ISO-8859-1"); // specify encoding
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        marshaller.marshal(obj, stream);
        try {
            return stream.toString(UTF8);
        } catch (UnsupportedEncodingException ex) {
            throw new JAXBException(ex.getCause());
        }
    }

    public static <T> T convertFromXml(String xml, Class<T> type) throws JAXBException {
        //Class<T> type = (Class<T>) ((ParameterizedType) getClass().getGenericSuperclass()).getActualTypeArguments()[0];
        JAXBContext jaxbContext = JAXBContext.newInstance(type);
        Unmarshaller unmarshaller = jaxbContext.createUnmarshaller();
        byte[] data;
        try {
            data = xml.getBytes(UTF8);
        } catch (UnsupportedEncodingException ex) {
            throw new JAXBException(ex.getCause());
        }
        ByteArrayInputStream stream = new ByteArrayInputStream(data);
        return (T) unmarshaller.unmarshal(stream);
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

    public static void catalina(String name, HttpServletResponse res) {
        File file = new File(String.format("%s/logs/%s", Catalina, name));
        res.setCharacterEncoding(UTF8);
        res.setContentType("application/octet-stream");
        res.setContentLength((int) file.length());
        res.setHeader("Content-Disposition", String.format("attachment; filename=\"%s\"", file.getName()));
        try (FileInputStream in = new FileInputStream(file)) {
            OutputStream out = res.getOutputStream();
            byte[] buffer = new byte[4096];
            int length;
            while ((length = in.read(buffer)) > 0) {
                out.write(buffer, 0, length);
            }
            out.flush();
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }
    //endregion
}
