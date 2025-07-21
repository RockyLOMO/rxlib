package org.springframework.service;

import io.netty.handler.codec.http.HttpHeaderValues;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Constants;
import org.rx.core.RxConfig;
import org.rx.core.Strings;
import org.rx.core.ThreadPool;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.ContentCachingRequestWrapper;

import javax.servlet.*;
import javax.servlet.annotation.WebFilter;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

import static org.rx.core.Sys.logCtx;

@Slf4j
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE)
@WebFilter(urlPatterns = "/*")
public class RWebConfig implements Filter {
    @RequiredArgsConstructor
    @RestController
    @RequestMapping("api")
    public static class Handler {
        final HandlerUtil util;

        @RequestMapping("health")
        public Object health(HttpServletRequest request, HttpServletResponse response) {
            return util.around(request, response);
        }
    }

    public static void enableTrace(String traceName) {
        if (traceName == null) {
            traceName = Constants.DEFAULT_TRACE_NAME;
        }
        RxConfig.ThreadPoolConfig conf = RxConfig.INSTANCE.getThreadPool();
        conf.setTraceName(traceName);
        ThreadPool.onTraceIdChanged.first((s, e) -> logCtx(conf.getTraceName(), e));
    }

    final HandlerUtil util;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest servletRequest = (HttpServletRequest) request;
        if (Strings.startsWithIgnoreCase(request.getContentType(), HttpHeaderValues.APPLICATION_JSON)
                && !(request instanceof ContentCachingRequestWrapper)) {
            request = new ContentCachingRequestWrapper(servletRequest);
//            log.info("MxWebFilter exchange ContentCachingRequest");
        }

        if (util.around(servletRequest, (HttpServletResponse) response)) {
            chain.doFilter(request, response);
        }
    }
}
