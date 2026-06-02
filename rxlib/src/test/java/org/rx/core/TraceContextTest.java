package org.rx.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class TraceContextTest {
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
    void shouldInheritTraceIdAndCleanWorkerThread() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            ThreadPool.startTrace("trace-parent");
            assertEquals("trace-parent",
                    executor.submit((Callable<String>) ThreadPool.Task.adapt((Callable<String>) ThreadPool::traceId)).get(3, TimeUnit.SECONDS));
            ThreadPool.clearTrace();

            assertNull(executor.submit((Callable<String>) ThreadPool.Task.adapt((Callable<String>) ThreadPool::traceId)).get(3, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldDiscardStaleTraceFromPlainExecutorThread() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            assertEquals("stale-trace", executor.submit(() -> {
                ThreadPool.startTrace("stale-trace");
                return ThreadPool.traceId();
            }).get(3, TimeUnit.SECONDS));

            ThreadPool.startTrace("trace-parent");
            assertEquals("trace-parent",
                    executor.submit((Callable<String>) ThreadPool.Task.adapt((Callable<String>) ThreadPool::traceId)).get(3, TimeUnit.SECONDS));
            ThreadPool.clearTrace();

            assertNull(executor.submit((Callable<String>) ThreadPool.Task.adapt((Callable<String>) ThreadPool::traceId)).get(3, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }
}
