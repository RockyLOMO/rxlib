package org.rx.util;

import com.google.common.net.HttpHeaders;
import io.netty.util.concurrent.FastThreadLocal;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ArrayUtils;
import org.rx.bean.DateTime;
import org.rx.bean.Tuple;
import org.rx.core.Contract;
import org.rx.core.NQuery;
import org.rx.core.Strings;
import org.rx.core.exception.InvalidException;
import org.rx.io.IOStream;
import org.rx.net.http.HttpClient;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.bind.ServletRequestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.InputStream;
import java.util.Date;

import static org.rx.core.Contract.*;

@Slf4j
public class Servlets extends ServletRequestUtils {
    private static FastThreadLocal<Tuple<HttpServletRequest, HttpServletResponse>> holder = new FastThreadLocal<>();

    public static void setRequest(HttpServletRequest request, HttpServletResponse response) {
        holder.set(Tuple.of(request, response));
    }

    public static Tuple<HttpServletRequest, HttpServletResponse> currentRequest() {
        Tuple<HttpServletRequest, HttpServletResponse> tuple = holder.getIfExists();
        if (tuple == null) {
            ServletRequestAttributes ra = (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
            tuple = Tuple.of(ra.getRequest(), ra.getResponse());
        }
        return tuple;
    }

    public static String requestIp() {
        HttpServletRequest request = currentRequest().left;

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

    public static String requestIp(boolean throwOnEmpty) {
        try {
            return requestIp();
        } catch (Exception e) {
            if (throwOnEmpty) {
                throw InvalidException.sneaky(e);
            }
            log.warn("requestIp {}", e.getMessage());
            return "0.0.0.0";
        }
    }

    @SneakyThrows
    public static void responseFile(IOStream<?, ?> stream) {
        require(stream);

        HttpServletResponse response = currentRequest().right;
        response.setCharacterEncoding(Contract.UTF_8);
        response.setContentType(MimeTypeUtils.APPLICATION_OCTET_STREAM_VALUE);
        response.setContentLength((int) stream.getLength());
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, String.format("attachment; filename=\"%s\"", HttpClient.encodeUrl(stream.getName())));
        stream.copyTo(response.getOutputStream());
    }

    @SneakyThrows
    public static void cacheResponse(int cacheSeconds, String contentType, InputStream in) {
        HttpServletResponse response = currentRequest().right;
        response.setHeader(HttpHeaders.CACHE_CONTROL, String.format("max-age=%s", cacheSeconds));
        response.setHeader(HttpHeaders.EXPIRES, new Date(DateTime.utcNow().addSeconds(cacheSeconds).getTime()).toString());
        response.setContentType(contentType);
        if (in != null) {
            IOStream.copyTo(in, response.getOutputStream());
        }
    }

    public static String getCookie(String name) {
        require(name);

        HttpServletRequest request = currentRequest().left;
        return quietly(() -> {
            if (ArrayUtils.isEmpty(request.getCookies())) {
                return null;
            }
            return NQuery.of(request.getCookies()).where(p -> p.getName().equals(name)).select(p -> HttpClient.decodeUrl(p.getValue())).firstOrDefault();
        });
    }

    public static void setCookie(String name, String value) {
        setCookie(name, value);
    }

    public static void setCookie(String name, String value, Date expire) {
        require(name);

        Cookie cookie = new Cookie(name, HttpClient.encodeUrl(value));
        cookie.setPath("/");
//        cookie.setSecure(true);
        cookie.setHttpOnly(true);
        if (expire != null) {
            cookie.setMaxAge((int) new DateTime(expire).subtract(DateTime.now()).getTotalSeconds());
        }
        currentRequest().right.addCookie(cookie);
    }

    public static void deleteCookie(String name) {
        require(name);

        Cookie cookie = new Cookie(name, null);
        cookie.setPath("/");
//        cookie.setSecure(true);
        cookie.setHttpOnly(true);
        cookie.setMaxAge(0);
        currentRequest().right.addCookie(cookie);
    }
}
