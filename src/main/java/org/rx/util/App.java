package org.rx.util;

import com.google.common.base.Strings;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.rx.common.DateTime;
import org.rx.common.FuncCallback2;
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
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * Created by wangxiaoming on 2016/1/25.
 */
public class App {
    //region Fields
    public static final String lHome = System.getProperty("catalina.home"), utf8 = "UTF-8";
    private static final String x2 = "logs";
    private static final Log log1 = LogFactory.getLog("helperInfo"), log2 = LogFactory.getLog("helperError");
    //静态不要new
//    private static final Random rnd = new Random();
    private static final NQuery<Class<?>> SupportTypes;
    private static final NQuery<SimpleDateFormat> SupportDateFormats;

    static {
        SupportTypes = new NQuery<>(new Class<?>[]{
                String.class,
                Boolean.class, Byte.class, Short.class, Integer.class, Long.class, Float.class, Double.class,
                BigDecimal.class, UUID.class, Date.class,
        });
        SupportDateFormats = new NQuery<>(new SimpleDateFormat[]{
                new SimpleDateFormat("yyyy-MM-dd HH:mm:ss"),
                new SimpleDateFormat("yyyyMMddHHmmss")
        });
    }

    private static Random getRandom() {
//        return rnd;
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
            prop.load(new InputStreamReader(App.class.getClassLoader().getResourceAsStream(propertiesFile + ".properties"), utf8));
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

    public static String readRequestBody(HttpServletRequest request) {
        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(request.getInputStream(), utf8))) {
            char[] charBuffer = new char[128];
            int bytesRead;
            while ((bytesRead = reader.read(charBuffer)) > 0) {
                body.append(charBuffer, 0, bytesRead);
            }
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
        return body.toString();
    }

    public static void check(String name, HttpServletResponse res) {
        File file = new File(String.format("%s/%s/%s", lHome, x2, name));
        res.setCharacterEncoding(utf8);
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

    public static <T> boolean equals(T obj1, T obj2) {
        if (obj1 == null) {
            if (obj2 == null) {
                return true;
            }
            return false;
        }
        if (obj2 == null) {
            return false;
        }
        return obj1.equals(obj2);
    }

    public static boolean equals(String str1, String str2, boolean ignoreCase) {
        if (str1 == null) {
            if (str2 == null) {
                return true;
            }
            return false;
        }
        if (str2 == null) {
            return false;
        }
        return ignoreCase ? str1.equals(str2) : str1.equalsIgnoreCase(str2);
    }

    public static <T> T isNull(T value, T defaultVal) {
        return isNull(value, defaultVal, false);
    }

    public static <T> T isNull(T value, T defaultVal, boolean trim) {
        if (value == null || (value instanceof String && (trim ? isNullOrWhiteSpace(value.toString()) : isNullOrEmpty(value.toString())))) {
            return defaultVal;
        }
        return value;
    }

    public static <T> T isNull(T value, FuncCallback2<T, T> defaultFun) {
        if (value == null || (value instanceof String && value.toString().length() == 0)) {
            if (defaultFun != null) {
                return defaultFun.invoke(value);
            }
            return null;
        }
        return value;
    }

    public static String stringJoin(String delimiter, Iterable<String> set) {
        StringBuilder str = new StringBuilder();
        for (String s : set) {
            if (str.length() == 0) {
                str.append(s);
                continue;
            }

            str.append(delimiter).append(s);
        }
        return str.toString();
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
        return tryConvert(val, toType).Item2;
    }

    public static <T> Tuple<Boolean, T> tryConvert(Object val, Class<T> toType) {
        return tryConvert(val, toType, null);
    }

    public static <T> Tuple<Boolean, T> tryConvert(Object val, Class<T> toType, T defaultVal) {
        if (val == null) {
            return new Tuple<>(true, null);
        }

        try {
            return new Tuple<>(true, changeType(val, toType));
        } catch (Exception ex) {
            return new Tuple<>(false, defaultVal);
        }
    }

    public static <T> T changeType(Object value, Class<T> toType) {
        final Class<?> fromType;
        if (value == null || toType.isInstance(value) || toType.equals(fromType = value.getClass())) {
            return (T) value;
        }
        Class<?> strType = SupportTypes.first();
        if (toType.equals(strType)) {
            return (T) value.toString();
        }

        if (!(SupportTypes.any(new FuncCallback2<Class<?>, Boolean>() {
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
                NQuery<String> q = SupportDateFormats.select(new FuncCallback2<SimpleDateFormat, String>() {
                    @Override
                    public String invoke(SimpleDateFormat arg) {
                        return arg.toPattern();
                    }
                });
                throw new RuntimeException(String.format("仅支持 %s format的日期转换", stringJoin(",", q)), lastEx);
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

        String pName = type.getName();
        String newName = "java.lang." + pName.substring(0, 1).toUpperCase() + pName.substring(1, pName.length());
        try {
            return Class.forName(newName);
        } catch (ClassNotFoundException ex) {
            throw new RuntimeException(String.format("checkType %s=>%s", type, newName), ex);
        }
    }

    //region base64
    private static final String base64Regex = "^([A-Za-z0-9+/]{4})*([A-Za-z0-9+/]{4}|[A-Za-z0-9+/]{3}=|[A-Za-z0-9+/]{2}==)$";

    public static boolean isBase64String(String base64) {
        if (isNullOrEmpty(base64)) {
            return false;
        }

        return Pattern.compile(base64Regex).matcher(base64).find();
    }

    public static String convertToBase64String(byte[] data) {
        byte[] ret = Base64.encodeBase64(data);
        try {
            return new String(ret, utf8);
        } catch (UnsupportedEncodingException ex) {
            throw new RuntimeException("convertToBase64String", ex);
        }
    }

    public static byte[] convertFromBase64String(String base64) {
        byte[] ret;
        try {
            ret = base64.getBytes(utf8);
        } catch (UnsupportedEncodingException ex) {
            throw new RuntimeException("convertFromBase64String", ex);
        }
        return Base64.decodeBase64(ret);
    }


    public static String serializeToBase64(Object obj) {
        byte[] data = serialize(obj);
        return convertToBase64String(data);
    }

    public static byte[] serialize(Object obj) {
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
    //endregion

    //region Flags
    private static final String F1 = "Flag", F2 = "Value";

    public static <T extends Enum<T>> void FlagsAdd(Enum<T> src, Enum<T>... vals) {
        int v1 = FlagsGetValue(src);
        for (Enum<T> val : vals) {
            v1 |= FlagsGetValue(val);
        }
        FlagsSetValue(src, v1);
    }

    public static <T extends Enum<T>> void FlagsRemove(Enum<T> src, Enum<T>... vals) {
        int v1 = FlagsGetValue(src);
        for (Enum<T> val : vals) {
            v1 &= ~FlagsGetValue(val);
        }
        FlagsSetValue(src, v1);
    }

    public static <T extends Enum<T>> boolean FlagsHas(Enum<T> src, Enum<T>... vals) {
        int v1 = FlagsGetValue(src), v2 = 0;
        for (Enum<T> val : vals) {
            v2 |= FlagsGetValue(val);
        }
        return (v1 & v2) == v2;
    }

    private static <T extends Enum<T>> int FlagsGetValue(Enum<T> src) {
        try {
            Field m = src.getClass().getField(F2);
            m.setAccessible(true);
            return m.getInt(src);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
    }

    private static <T extends Enum<T>> void FlagsSetValue(Enum<T> src, int val) {
        try {
            Field m = src.getClass().getField(F2);
            m.setAccessible(true);
            m.setInt(src, val);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
    }

    public static <T extends Enum<T>> String FlagsToString(Enum<T> src) {
        int Value = FlagsGetValue(src);
        try {
            Class enumType = src.getClass();
            Field m = enumType.getField(F1);
            m.setAccessible(true);
            StringBuilder str = new StringBuilder();
            for (Object item : enumType.getEnumConstants()) {
                int flag = m.getInt(item);
                if (flag == 0 || (Value & flag) != flag) {
                    continue;
                }
                if (str.length() == 0) {
                    str.append(item.toString());
                    continue;
                }
                str.append(", " + item.toString());
            }
            return str.toString();
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
    }
    //endregion
    //endregion

    //region Xml
    public static <T> String convertToXml(T obj) throws JAXBException {
        JAXBContext jaxbContext = JAXBContext.newInstance(obj.getClass());
        Marshaller marshaller = jaxbContext.createMarshaller();
        //marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true); // pretty
        //marshaller.setProperty(Marshaller.JAXB_ENCODING, "ISO-8859-1"); // specify encoding
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        marshaller.marshal(obj, stream);
        try {
            return stream.toString(utf8);
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
            data = xml.getBytes(utf8);
        } catch (UnsupportedEncodingException ex) {
            throw new JAXBException(ex.getCause());
        }
        ByteArrayInputStream stream = new ByteArrayInputStream(data);
        return (T) unmarshaller.unmarshal(stream);
    }
    //endregion

    //region HttpClient
    private static final String UserAgent = "Mozilla/5.0 (Linux; Android 5.1.1; Nexus 5 Build/LMY48B; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/43.0.2357.65 Mobile Safari/537.36";

    public static String httpGet(String url) {
        try {
            URL uri = new URL(url);
            HttpURLConnection client = (HttpURLConnection) uri.openConnection();
            client.setRequestMethod("GET");
            client.setRequestProperty("User-Agent", UserAgent);

            int resCode = client.getResponseCode();

            StringBuilder resText = new StringBuilder();
            try (BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()))) {
                String line;
                while ((line = in.readLine()) != null) {
                    resText.append(line);
                }
            }
            return resText.toString();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    public static String urlEncode(String val) {
        try {
            return URLEncoder.encode(val, utf8);
        } catch (UnsupportedEncodingException ex) {
            throw new RuntimeException(ex);
        }
    }

    public static Map<String, String> parseQueryString(String queryString) {
        Map<String, String> map = new LinkedHashMap<>();
        if (queryString == null) {
            return map;
        }

        String[] pairs = queryString.split("&");
        try {
            for (String pair : pairs) {
                int idx = pair.indexOf("=");
                String key = idx > 0 ? URLDecoder.decode(pair.substring(0, idx), utf8) : pair;
                String value = idx > 0 && pair.length() > idx + 1 ? URLDecoder.decode(pair.substring(idx + 1), utf8) : null;
                map.put(key, value);
            }
        } catch (UnsupportedEncodingException ex) {
            throw new RuntimeException(ex);
        }
        return map;
    }

    public static String buildQueryString(String baseUrl, Map<String, String> params) {
        if (params == null) {
            return baseUrl;
        }
        if (baseUrl == null) {
            baseUrl = "";
        }

        String c = baseUrl.indexOf("?") == -1 ? "?" : "&";
        StringBuilder url = new StringBuilder(baseUrl);
        for (String key : params.keySet()) {
            String val = params.get(key);
            url.append(url.length() == baseUrl.length() ? c : "&")
                    .append(urlEncode(key)).append("=").append(val == null ? "" : urlEncode(val));
        }
        return url.toString();
    }
    //endregion
}
