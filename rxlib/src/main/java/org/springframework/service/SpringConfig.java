package org.springframework.service;

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

import javax.annotation.PostConstruct;
import javax.validation.Validation;
import javax.validation.Validator;
import java.io.File;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

@Configuration
@ComponentScan("org.springframework.service")
@ServletComponentScan
@EnableAspectJAutoProxy(proxyTargetClass = true, exposeProxy = true)
public class SpringConfig {
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
    public FilterRegistrationBean<RWebFilter> rWebFilter() {
        FilterRegistrationBean<RWebFilter> bean = new FilterRegistrationBean<>(new RWebFilter());
        bean.setName("rWebFilter");
        bean.addUrlPatterns("/*");
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return bean;
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
