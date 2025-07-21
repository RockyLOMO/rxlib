package org.springframework.service;

import io.netty.handler.codec.http.HttpHeaderValues;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.rx.core.Constants;
import org.rx.core.RxConfig;
import org.rx.core.Strings;
import org.rx.core.ThreadPool;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.util.ContentCachingRequestWrapper;

import javax.servlet.*;
import javax.servlet.annotation.WebFilter;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

import static org.rx.core.Sys.logCtx;

//WebMvcConfigurationSupport
@Slf4j
@RequiredArgsConstructor
@Configuration
public class RWebConfig implements WebMvcConfigurer {
    public static void enableTrace(String traceName) {
        if (traceName == null) {
            traceName = Constants.DEFAULT_TRACE_NAME;
        }
        RxConfig.ThreadPoolConfig conf = RxConfig.INSTANCE.getThreadPool();
        conf.setTraceName(traceName);
        ThreadPool.onTraceIdChanged.first((s, e) -> logCtx(conf.getTraceName(), e));
    }

    public static void beginTrace(HttpServletRequest request, HttpServletResponse response) {
        RxConfig.ThreadPoolConfig conf = RxConfig.INSTANCE.getThreadPool();
        String traceName = conf.getTraceName();
        if (Strings.isEmpty(traceName)) {
            return;
        }

        String parentTraceId = request.getHeader(traceName);
        String traceId = ThreadPool.startTrace(parentTraceId);
        response.setHeader(traceName, traceId);
    }

    public static void endTrace(HttpServletRequest request, HttpServletResponse response) {
        ThreadPool.endTrace();
    }

    final HandlerImpl handler;
    final FilterImpl filter;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(handler)
                .addPathPatterns("/**")
                .order(Ordered.HIGHEST_PRECEDENCE);
    }

    @Bean
    public FilterRegistrationBean<FilterImpl> filter() {
        FilterRegistrationBean<FilterImpl> bean = new FilterRegistrationBean<>(filter);
        bean.addUrlPatterns("/*");
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return bean;
    }

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

    @Aspect
    @RequiredArgsConstructor
    @Component
    public static class HandlerAspect {
        final HandlerUtil util;

        @Around("within(@org.springframework.web.bind.annotation.RestController *)")
        public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
            ServletRequestAttributes ra = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (ra == null) {
                return joinPoint.proceed();
            }
            try {
                if (!util.around(ra.getRequest(), ra.getResponse())) {
                    return null;
                }

                beginTrace(ra.getRequest(), ra.getResponse());
                return joinPoint.proceed();
            } finally {
                endTrace(ra.getRequest(), ra.getResponse());
            }
        }
    }

    @RequiredArgsConstructor
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @Component
    public static class HandlerImpl implements HandlerInterceptor {
        final HandlerUtil util;

        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
            if (!util.around(request, response)) {
                return false;
            }

            beginTrace(request, response);
            return true;
        }

        @Override
        public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
            endTrace(request, response);
        }
    }

    @RequiredArgsConstructor
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @Component
    @WebFilter(urlPatterns = "/*")
    public static class FilterImpl implements Filter {
        final HandlerUtil util;

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
            HttpServletRequest servletRequest = (HttpServletRequest) request;
            HttpServletResponse servletResponse = (HttpServletResponse) response;
            if (Strings.startsWithIgnoreCase(request.getContentType(), HttpHeaderValues.APPLICATION_JSON)
                    && !(request instanceof ContentCachingRequestWrapper)) {
                request = new ContentCachingRequestWrapper(servletRequest);
                log.debug("RWebConfigurer rebuild ContentCachingRequest");
            }

            if (util.around(servletRequest, servletResponse)) {
                chain.doFilter(request, response);
            }
        }
    }
}
