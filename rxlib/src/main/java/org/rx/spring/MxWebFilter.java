package org.rx.spring;

import io.netty.handler.codec.http.HttpHeaderValues;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Strings;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.util.ContentCachingRequestWrapper;

import javax.servlet.*;
import javax.servlet.annotation.WebFilter;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;

@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@WebFilter(urlPatterns = "/*")
public class MxWebFilter implements Filter {
    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain) throws IOException, ServletException {
        if (Strings.startsWithIgnoreCase(servletRequest.getContentType(), HttpHeaderValues.APPLICATION_JSON)
                && !(servletRequest instanceof ContentCachingRequestWrapper)) {
            servletRequest = new ContentCachingRequestWrapper((HttpServletRequest) servletRequest);
            log.info("MxWebFilter exchange ContentCachingRequest");
        }
        filterChain.doFilter(servletRequest, servletResponse);
    }
}
