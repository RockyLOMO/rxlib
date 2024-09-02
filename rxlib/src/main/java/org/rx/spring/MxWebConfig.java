package org.rx.spring;

import lombok.RequiredArgsConstructor;
import org.rx.core.Constants;
import org.rx.core.RxConfig;
import org.rx.core.Strings;
import org.rx.core.ThreadPool;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import static org.rx.core.Sys.logCtx;

//WebMvcConfigurationSupport
@RequiredArgsConstructor
@Configuration
public class MxWebConfig implements WebMvcConfigurer {
    @Component
    public static class WebTracer implements HandlerInterceptor {
        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
            RxConfig.ThreadPoolConfig conf = RxConfig.INSTANCE.getThreadPool();
            String traceName = conf.getTraceName();
            if (Strings.isEmpty(traceName)) {
                return true;
            }

            String parentTraceId = request.getHeader(traceName);
            String traceId = ThreadPool.startTrace(parentTraceId);
            response.setHeader(traceName, traceId);
            return true;
        }

        @Override
        public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
            ThreadPool.endTrace();
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

    final WebTracer tracer;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tracer).addPathPatterns("/**");
    }
}
