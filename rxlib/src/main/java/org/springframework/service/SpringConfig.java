package org.springframework.service;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.rx.annotation.Subscribe;
import org.rx.bean.Decimal;
import org.rx.core.*;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.socks.SocksContext;
import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletComponentScan;
import org.springframework.context.annotation.*;
import org.springframework.core.Ordered;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.PostConstruct;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.validation.Validation;
import javax.validation.Validator;
import java.io.File;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

@RequiredArgsConstructor
@Configuration
@ComponentScan("org.springframework.service")
@ServletComponentScan
@EnableAspectJAutoProxy(proxyTargetClass = true, exposeProxy = true)
public class SpringConfig implements WebMvcConfigurer { //WebMvcConfigurationSupport
    @RequiredArgsConstructor
    @Component
    public static class WebTracer implements HandlerInterceptor {
        final HandlerUtil util;

        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
            if (util.around(request, response)) {
                return false;
            }

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

    final WebTracer tracer;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tracer).addPathPatterns("/**");
    }

    @Bean
    public FilterRegistrationBean<RWebConfig> rWebFilter(HandlerUtil util) {
        FilterRegistrationBean<RWebConfig> bean = new FilterRegistrationBean<>(new RWebConfig(util));
        bean.setName("rWebFilter");
        bean.addUrlPatterns("/*");
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return bean;
    }

    @Primary
    @Bean
    public AsyncTaskExecutor asyncTaskExecutorEx() {
        return new AsyncTaskExecutor() {
            @Override
            public void execute(Runnable task, long startTimeout) {
                Tasks.executor().execute(task);
            }

            @Override
            public Future<?> submit(Runnable task) {
                return Tasks.executor().submit(task);
            }

            @Override
            public <T> Future<T> submit(Callable<T> task) {
                return Tasks.executor().submit(task);
            }

            @Override
            public void execute(Runnable task) {
                Tasks.executor().execute(task);
            }
        };
    }

    @Primary
    @Bean
    public ExecutorService executorServiceEx() {
        return Tasks.executor();
    }

    @Bean
    public Validator validator() {
        return Validation.buildDefaultValidatorFactory().getValidator();
    }

    //DataSize 和 Duration
    @Component
    @ConfigurationPropertiesBinding
    public static class DecimalConverter implements Converter<Object, Decimal> {
        @Override
        public Decimal convert(Object s) {
            return Decimal.valueOf(s.toString());
        }
    }

    @Component
    @ConfigurationPropertiesBinding
    public static class AuthenticEndpointConverter implements Converter<String, AuthenticEndpoint> {
        @Override
        public AuthenticEndpoint convert(String s) {
            return AuthenticEndpoint.valueOf(s);
        }
    }

    @Component
    @ConfigurationPropertiesBinding
    public static class FileConverter implements Converter<String, File> {
        @Override
        public File convert(String s) {
            return new File(s);
        }
    }

    @Component
    @ConfigurationPropertiesBinding
    public static class ClassConverter implements Converter<String, Class<?>> {
        @Override
        public Class<?> convert(String s) {
            return Reflects.loadClass(s, false);
        }
    }

    @SneakyThrows
    @PostConstruct
    public void init() {
        Class.forName(Sys.class.getName());
        ObjectChangeTracker.DEFAULT.register(this);
    }

    @Subscribe(topicClass = RxConfig.class)
    void onChanged(ObjectChangedEvent event) {
        //todo all topic
        Tasks.setTimeout(() -> {
            String omega = event.<RxConfig>source().getOmega();
            if (omega != null) {
                SocksContext.omega(omega);
            }
        }, 60 * 1000);
    }
}
