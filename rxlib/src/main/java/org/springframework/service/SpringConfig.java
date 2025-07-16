package org.springframework.service;

import org.rx.bean.Decimal;
import org.rx.core.Reflects;
import org.rx.core.Tasks;
import org.rx.net.AuthenticEndpoint;
import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.context.annotation.*;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Component;

import javax.validation.Validation;
import javax.validation.Validator;
import java.io.File;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

@Configuration
@ComponentScan("org.springframework.service")
@EnableAspectJAutoProxy(proxyTargetClass = true, exposeProxy = true)
public class SpringConfig
//        implements AsyncConfigurer
{
//    @Override
//    public Executor getAsyncExecutor() {
//        return new ThreadPool("rx-spring-1");
//    }
//
//    @Override
//    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
//        return (e, m, a) -> TraceHandler.INSTANCE.log(e);
//    }

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
}
