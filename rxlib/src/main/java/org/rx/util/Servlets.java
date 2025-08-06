package org.rx.util;

import io.netty.util.concurrent.FastThreadLocal;
import lombok.NonNull;
import lombok.SneakyThrows;
import org.apache.commons.lang3.ArrayUtils;
import org.rx.bean.DateTime;
import org.rx.bean.Tuple;
import org.rx.core.Linq;
import org.rx.core.Strings;
import org.rx.exception.InvalidException;
import org.rx.exception.TraceHandler;
import org.rx.io.IOStream;
import org.rx.net.http.HttpClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.ServletRequestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.rx.core.Extends.quietly;

public class Servlets extends ServletRequestUtils {
    static final FastThreadLocal<Tuple<HttpServletRequest, HttpServletResponse>> CTX = new FastThreadLocal<>();

    public static Tuple<HttpServletRequest, HttpServletResponse> currentRequest() {
        Tuple<HttpServletRequest, HttpServletResponse> tuple = CTX.getIfExists();
        if (tuple == null) {
            ServletRequestAttributes ra = (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
            //inject response may have issues
            tuple = Tuple.of(ra.getRequest(), ra.getResponse());
        }
        return tuple;
    }

    public static String requestIp() {
        HttpServletRequest request = currentRequest().left;

        String ip = request.getHeader("X-Forwarded-For");
        if (Strings.isBlank(ip)) {
            ip = request.getHeader("x-real-ip");
        }
        if (Strings.isBlank(ip)) {
            ip = request.getHeader("HTTP_X_FORWARDED_FOR");
        }
//        if (Strings.isEmpty(ip) || "unknown".equalsIgnoreCase(ip)) {
//            ip = request.getHeader("Proxy-Client-IP");
//        }
//        if (Strings.isEmpty(ip) || "unknown".equalsIgnoreCase(ip)) {
//            ip = request.getHeader("WL-Proxy-Client-IP");
//        }
//        if (Strings.isEmpty(ip) || "unknown".equalsIgnoreCase(ip)) {
//            ip = request.getHeader("HTTP_CLIENT_IP");
//        }
        if (Strings.isBlank(ip)) {
            ip = request.getRemoteAddr();
        }
        String[] ips = Strings.split(ip, ",");
        if (ips.length > 1) {
            ip = ips[0];
            //.trim();
        }
        return ip;
    }

    public static String requestIp(boolean throwOnEmpty) {
        try {
            return requestIp();
        } catch (Throwable e) {
            if (throwOnEmpty) {
                throw InvalidException.sneaky(e);
            }
            TraceHandler.INSTANCE.log(e);
            return "0.0.0.0";
        }
    }

    public static void responseFile(IOStream stream) {
        responseFile(stream, MediaType.APPLICATION_OCTET_STREAM_VALUE);
    }

    @SneakyThrows
    public static void responseFile(@NonNull IOStream stream, String contentType) {
        HttpServletResponse response = currentRequest().right;
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(contentType);
        response.setContentLength((int) stream.getLength());
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, String.format("attachment; filename=\"%s\"", HttpClient.encodeUrl(stream.getName())));
        stream.read(response.getOutputStream());
    }

    @SneakyThrows
    public static void cacheResponse(int cacheSeconds, String contentType, InputStream in) {
        HttpServletResponse response = currentRequest().right;
        response.setHeader(HttpHeaders.CACHE_CONTROL, String.format("max-age=%s", cacheSeconds));
        response.setHeader(HttpHeaders.EXPIRES, new Date(DateTime.now().addSeconds(cacheSeconds).getTime()).toString());
        response.setContentType(contentType);
        if (in != null) {
            IOStream.copy(in, IOStream.NON_READ_FULLY, response.getOutputStream());
        }
    }

    public static String getCookie(@NonNull String name) {
        HttpServletRequest request = currentRequest().left;
        return quietly(() -> {
            if (ArrayUtils.isEmpty(request.getCookies())) {
                return null;
            }
            return Linq.from(request.getCookies()).where(p -> p.getName().equals(name)).select(p -> HttpClient.decodeUrl(p.getValue())).firstOrDefault();
        });
    }

    public static void setCookie(String name, String value) {
        setCookie(name, value, null);
    }

    public static void setCookie(@NonNull String name, String value, Date expire) {
        Cookie cookie = new Cookie(name, HttpClient.encodeUrl(value));
        cookie.setPath("/");
//        cookie.setSecure(true);
        cookie.setHttpOnly(true);
        if (expire != null) {
            cookie.setMaxAge((int) DateTime.of(expire).subtract(DateTime.now()).getTotalSeconds());
        }
        currentRequest().right.addCookie(cookie);
    }

    public static void deleteCookie(@NonNull String name) {
        Cookie cookie = new Cookie(name, null);
        cookie.setPath("/");
//        cookie.setSecure(true);
        cookie.setHttpOnly(true);
        cookie.setMaxAge(0);
        currentRequest().right.addCookie(cookie);
    }
}
