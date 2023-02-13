package org.rx.spring;

import org.rx.bean.Decimal;
import org.rx.core.Reflects;
import org.rx.net.AuthenticEndpoint;
import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

import javax.validation.Validation;
import javax.validation.Validator;
import java.io.File;

@Configuration
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

//    @Bean("defaultExecutorService")
//    public ExecutorService executorService() {
//        return Tasks.executor();
//    }

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
