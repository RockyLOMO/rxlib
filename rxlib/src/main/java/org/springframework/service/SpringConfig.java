package org.springframework.service;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.annotation.Subscribe;
import org.rx.bean.Decimal;
import org.rx.core.*;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.socks.SocksContext;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.boot.web.servlet.ServletComponentScan;
import org.springframework.context.annotation.*;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.util.ReflectionUtils;

import javax.annotation.PostConstruct;
import javax.validation.Validation;
import javax.validation.Validator;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

@Slf4j
@RequiredArgsConstructor
@Configuration
@ComponentScan("org.springframework.service")
@ServletComponentScan
@EnableAspectJAutoProxy(proxyTargetClass = true, exposeProxy = true)
public class SpringConfig {
    static final TaskDecorator TRACE_CONTEXT_TASK_DECORATOR = new TraceContextTaskDecorator();

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
    public static BeanPostProcessor traceContextExecutorBeanPostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
                applyTraceTaskDecorator(bean, beanName);
                return bean;
            }
        };
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

    static void applyTraceTaskDecorator(Object bean, String beanName) {
        if (bean instanceof ThreadPool) {
            return;
        }
        Method method = ReflectionUtils.findMethod(bean.getClass(), "setTaskDecorator", TaskDecorator.class);
        if (method == null) {
            return;
        }
        TaskDecorator current = readTaskDecorator(bean);
        TaskDecorator next = current == null || isTraceTaskDecorator(current)
                ? TRACE_CONTEXT_TASK_DECORATOR : new CompositeTraceTaskDecorator(current);
        try {
            ReflectionUtils.makeAccessible(method);
            method.invoke(bean, next);
        } catch (Exception e) {
            log.warn("Set trace task decorator failed, bean={}", beanName, e);
        }
    }

    private static boolean isTraceTaskDecorator(TaskDecorator decorator) {
        return decorator instanceof TraceContextTaskDecorator || decorator instanceof CompositeTraceTaskDecorator;
    }

    private static TaskDecorator readTaskDecorator(Object bean) {
        Field field = ReflectionUtils.findField(bean.getClass(), "taskDecorator");
        if (field == null && bean instanceof ThreadPoolTaskExecutor) {
            field = ReflectionUtils.findField(ThreadPoolTaskExecutor.class, "taskDecorator");
        }
        if (field == null) {
            return null;
        }
        try {
            ReflectionUtils.makeAccessible(field);
            Object value = field.get(bean);
            return value instanceof TaskDecorator ? (TaskDecorator) value : null;
        } catch (Exception e) {
            return null;
        }
    }

    static class TraceContextTaskDecorator implements TaskDecorator {
        @Override
        public Runnable decorate(Runnable runnable) {
            return (Runnable) ThreadPool.Task.adapt(runnable);
        }
    }

    static class CompositeTraceTaskDecorator implements TaskDecorator {
        final TaskDecorator parent;

        CompositeTraceTaskDecorator(TaskDecorator parent) {
            this.parent = parent;
        }

        @Override
        public Runnable decorate(Runnable runnable) {
            return parent.decorate((Runnable) ThreadPool.Task.adapt(runnable));
        }
    }
}
