package org.rx.core;

import com.google.common.net.HttpHeaders;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.SystemUtils;
import org.rx.bean.*;
import org.rx.io.IOStream;
import org.rx.security.MD5Util;
import org.rx.socks.http.HttpClient;
import org.rx.io.MemoryStream;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

import static org.rx.core.Contract.*;

@Slf4j
public class App extends SystemUtils {
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
    //endregion

    //region Base64
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
        return new String(ret, StandardCharsets.UTF_8);
    }

    @SneakyThrows
    public static byte[] convertFromBase64String(String base64) {
        require(base64);

        byte[] data = base64.getBytes(StandardCharsets.UTF_8);
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
        response.setCharacterEncoding(Contract.UTF_8);
        response.setContentType(MimeTypeUtils.APPLICATION_OCTET_STREAM_VALUE);
        response.setContentLength((int) file.length());
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, String.format("attachment; filename=\"%s\"", file.getName()));
        try (FileInputStream in = new FileInputStream(file)) {
            IOStream.copyTo(in, response.getOutputStream());
        }
    }

    @SneakyThrows
    public static void cacheResponse(HttpServletResponse response, int cacheSeconds, String contentType, InputStream in) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, String.format("max-age=%s", cacheSeconds));
        response.setHeader(HttpHeaders.EXPIRES, new Date(DateTime.utcNow().addSeconds(cacheSeconds).getTime()).toString());
        response.setContentType(contentType);
        if (in != null) {
            IOStream.copyTo(in, response.getOutputStream());
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
        cookie.setMaxAge(0);
        response.addCookie(cookie);
    }
    //endregion
}
