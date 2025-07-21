package org.springframework.service;

import io.netty.handler.codec.http.HttpHeaderValues;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Strings;
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

@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@WebFilter(urlPatterns = "/*")
public class RWebFilter implements Filter {
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

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        if (Strings.startsWithIgnoreCase(request.getContentType(), HttpHeaderValues.APPLICATION_JSON)
                && !(request instanceof ContentCachingRequestWrapper)) {
            request = new ContentCachingRequestWrapper((HttpServletRequest) request);
//            log.info("MxWebFilter exchange ContentCachingRequest");
        }
        chain.doFilter(request, response);
    }
}
