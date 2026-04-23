package org.springframework.service;

import com.alibaba.fastjson2.JSONObject;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.util.concurrent.FastThreadLocal;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.rx.codec.CodecUtil;
import org.rx.codec.XChaCha20Poly1305Util;
import org.rx.core.Reflects;
import org.rx.core.RxConfig;
import org.rx.core.Strings;
import org.rx.diagnostic.DiagnosticHttpHandler;
import org.rx.io.Bytes;
import org.rx.io.DuplexStream;
import org.rx.net.NetEventWait;
import org.rx.net.Sockets;
import org.rx.net.http.ServerRequest;
import org.rx.net.http.ServerResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.rx.core.Extends.eq;
import static org.rx.core.Extends.ifNull;
import static org.rx.core.Sys.toJsonObject;
import static org.rx.core.Sys.toJsonString;

@Slf4j
@Component
public class HandlerUtil {
    static final String DIAGNOSTIC_PATH = "/rdiag";
    final DiagnosticHttpHandler diagnosticHandler = new DiagnosticHttpHandler();

    @SneakyThrows
    public boolean around(HttpServletRequest request, HttpServletResponse response) {
        if (isDiagnosticRequest(request)) {
            renderDiagnostic(request, response);
            return false;
        }

        int x = Reflects.convertQuietly(request.getParameter("x"), Integer.class, -1);
        Object resText = "0";
        try {
            switch (x) {
                case 1:
                    String multicast = request.getParameter("multicast");
                    if (multicast != null) {
                        String group = request.getParameter("group");
                        Integer mcId = Reflects.changeType(request.getParameter("mcId"), Integer.class);
                        NetEventWait.multicastLocal(Sockets.parseEndpoint(multicast), group, ifNull(mcId, 0));
                        resText = "1";
                    }
                    break;
            }
        } catch (Throwable e) {
            resText = String.format("%s\n%s", e, ExceptionUtils.getStackTrace(e));
        }
        String r = resText instanceof String ? (String) resText : toJsonString(resText);
        response.setContentType(MediaType.TEXT_PLAIN_VALUE);
        response.setCharacterEncoding("UTF-8");
        PrintWriter out = response.getWriter();
        out.write(r);
        out.flush();
        return false;
    }

    boolean isDiagnosticRequest(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (!Strings.isEmpty(contextPath) && path != null && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        return DIAGNOSTIC_PATH.equals(path);
    }

    void renderDiagnostic(HttpServletRequest servletRequest, HttpServletResponse servletResponse) throws Throwable {
        ServerRequest request = toServerRequest(servletRequest);
        ServerResponse response = new ServerResponse();
        diagnosticHandler.handle(request, response);
        servletResponse.setStatus(response.getStatus().code());
        for (Map.Entry<String, String> header : response.getHeaders()) {
            servletResponse.setHeader(header.getKey(), header.getValue());
        }
        ByteBuf content = response.getContent();
        if (content == null) {
            return;
        }
        try {
            byte[] body = new byte[content.readableBytes()];
            content.getBytes(content.readerIndex(), body);
            servletResponse.getOutputStream().write(body);
        } finally {
            Bytes.release(content);
        }
    }

    ServerRequest toServerRequest(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (!Strings.isEmpty(request.getQueryString())) {
            uri += "?" + request.getQueryString();
        }
        ServerRequest serverRequest = new ServerRequest(remoteEndpoint(request), uri, HttpMethod.valueOf(request.getMethod()));
        for (String name : Collections.list(request.getHeaderNames())) {
            for (String value : Collections.list(request.getHeaders(name))) {
                serverRequest.getHeaders().add(name, value);
            }
        }
        QueryStringDecoder decoder = new QueryStringDecoder(uri);
        for (Map.Entry<String, List<String>> entry : decoder.parameters().entrySet()) {
            for (String value : entry.getValue()) {
                serverRequest.getQueryString().put(entry.getKey(), value);
            }
        }
        return serverRequest;
    }

    InetSocketAddress remoteEndpoint(HttpServletRequest request) {
        return new InetSocketAddress(ifNull(request.getRemoteAddr(), "0.0.0.0"), Math.max(0, request.getRemotePort()));
    }
}
