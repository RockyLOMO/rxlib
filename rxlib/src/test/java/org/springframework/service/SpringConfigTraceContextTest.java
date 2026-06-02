package org.springframework.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rx.core.RxConfig;
import org.rx.core.ThreadPool;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SpringConfigTraceContextTest {
    private long slowMethodElapsedMicros;

    @BeforeEach
    void setUp() {
        slowMethodElapsedMicros = RxConfig.INSTANCE.getTrace().getSlowMethodElapsedMicros();
        RxConfig.INSTANCE.getTrace().setSlowMethodElapsedMicros(0);
    }

    @AfterEach
    void tearDown() {
        RxConfig.INSTANCE.getTrace().setSlowMethodElapsedMicros(slowMethodElapsedMicros);
        ThreadPool.clearTrace();
    }

    @Test
    void shouldDecorateSpringThreadPoolTaskExecutorByDefault() throws Exception {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);

        BeanPostProcessor processor = SpringConfig.traceContextExecutorBeanPostProcessor();
        processor.postProcessBeforeInitialization(executor, "testExecutor");
        executor.initialize();
        try {
            ThreadPool.startTrace("spring-trace");
            assertEquals("spring-trace", executor.submit(ThreadPool::traceId).get(3, TimeUnit.SECONDS));
            ThreadPool.clearTrace();

            assertNull(executor.submit(ThreadPool::traceId).get(3, TimeUnit.SECONDS));
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void shouldKeepExistingTaskDecorator() throws Exception {
        AtomicBoolean parentDecorated = new AtomicBoolean();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.setTaskDecorator(new TaskDecorator() {
            @Override
            public Runnable decorate(Runnable runnable) {
                return new Runnable() {
                    @Override
                    public void run() {
                        parentDecorated.set(true);
                        runnable.run();
                    }
                };
            }
        });

        BeanPostProcessor processor = SpringConfig.traceContextExecutorBeanPostProcessor();
        processor.postProcessBeforeInitialization(executor, "testExecutorWithDecorator");
        executor.initialize();
        try {
            ThreadPool.startTrace("spring-trace-existing");
            assertEquals("spring-trace-existing", executor.submit(ThreadPool::traceId).get(3, TimeUnit.SECONDS));
            assertTrue(parentDecorated.get());
        } finally {
            executor.shutdown();
        }
    }
}
