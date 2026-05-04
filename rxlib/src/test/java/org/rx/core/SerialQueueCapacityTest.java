package org.rx.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SerialQueueCapacityTest {
    private ThreadPool pool;

    @AfterEach
    void tearDown() {
        if (pool != null && !pool.isShutdown()) {
            pool.shutdownNow();
        }
        ThreadPool.taskSerialMap.clear();
        ThreadPool.taskSerialCountMap.clear();
    }

    @Test
    void serialTaskExceptionShouldNotStopLaterSameIdTasks() throws Exception {
        pool = ThreadPool.fixed("SERIAL-EXCEPTION", 2, 8);
        String taskId = "serial-ex-" + UUID.randomUUID();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger counter = new AtomicInteger();

        CompletableFuture<Integer> first = pool.runSerialAsync(new org.rx.util.function.Func<Integer>() {
            @Override
            public Integer invoke() throws Throwable {
                started.countDown();
                release.await(5, TimeUnit.SECONDS);
                throw new IllegalStateException("boom");
            }
        }, taskId);
        assertTrue(started.await(3, TimeUnit.SECONDS));

        CompletableFuture<Integer> second = pool.runSerialAsync(new org.rx.util.function.Func<Integer>() {
            @Override
            public Integer invoke() {
                return counter.incrementAndGet();
            }
        }, taskId);
        release.countDown();

        ExecutionException error = assertThrows(ExecutionException.class, () -> first.get(5, TimeUnit.SECONDS));
        assertTrue(error.getCause() instanceof IllegalStateException);
        assertEquals(1, second.get(5, TimeUnit.SECONDS).intValue());
        assertEquals(1, counter.get());

        waitUntil(new Condition() {
            @Override
            public boolean ok() {
                return !ThreadPool.taskSerialMap.containsKey(taskId)
                        && !ThreadPool.taskSerialCountMap.containsKey(taskId);
            }
        }, 3000);
        assertFalse(ThreadPool.taskSerialMap.containsKey(taskId));
    }

    private void waitUntil(Condition condition, long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (!condition.ok() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(condition.ok());
    }

    private interface Condition {
        boolean ok();
    }
}
