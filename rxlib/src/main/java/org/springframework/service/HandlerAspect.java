package org.springframework.service;

import com.alibaba.fastjson2.JSONObject;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.codec.CodecUtil;
import org.rx.codec.XChaCha20Poly1305Util;
import org.rx.core.Arrays;
import org.rx.core.Linq;
import org.rx.core.RxConfig;
import org.rx.core.Strings;
import org.rx.io.IOStream;
import org.rx.net.http.HttpClient;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.nio.charset.StandardCharsets;

import static org.rx.core.Extends.eq;
import static org.rx.core.Sys.toJsonObject;

@Slf4j
public class HandlerAspect {
    static final String AUTH_NAME = "x-token";
    static final String PARAMS_NAME = "_r";

    public void around(HttpServletRequest request, HttpServletResponse response) {

    }


    @SneakyThrows
    JSONObject getParams(HttpServletRequest request) {
        try {
            String b = request.getParameter(PARAMS_NAME);
            if (b == null) {
                b = IOStream.readString(request.getInputStream(), StandardCharsets.UTF_8);
            }
            if (Strings.isBlank(b)) {
                return null;
            }

            b = new String(XChaCha20Poly1305Util.decrypt(CodecUtil.convertFromBase64(b)), StandardCharsets.UTF_8);
            if (Strings.startsWith(b, "https")) {
                b = new HttpClient().get(b).toString();
            }
            return toJsonObject(b);
        } catch (Throwable e) {
            log.debug("around {}", e.toString());
            return null;
        }
    }

    boolean auth(HttpServletRequest request) {
        String s = RxConfig.INSTANCE.getMxpwd();
        return eq(request.getHeader(AUTH_NAME), s) || eq(request.getParameter(AUTH_NAME), s);
    }
}
