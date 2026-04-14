package org.rx.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ThreadPoolWheelTimerRegressionTest {
    private ThreadPool pool;
    private WheelTimer timer;

    @AfterEach
    void tearDown() {
        if (timer != null) {
            timer.shutdownNow();
        }
        if (pool != null && !pool.isShutdown()) {
            pool.shutdownNow();
        }
        ThreadPool.taskSerialMap.clear();
        ThreadPool.taskSerialCountMap.clear();
        ThreadPool.CTX_TRACE_ID.get().clear();
    }

    @Test
    void runSerialAsyncShouldClearMapAfterFastCompletion() throws Exception {
        pool = new ThreadPool(2, 32, null, "SERIAL-TEST");
        String taskId = "serial-" + UUID.randomUUID();

        CompletableFuture<Integer> first = pool.runSerialAsync(() -> 1, taskId);
        CompletableFuture<Integer> second = pool.runSerialAsync(() -> 2, taskId);

        assertEquals(1, first.get(5, TimeUnit.SECONDS).intValue());
        assertEquals(2, second.get(5, TimeUnit.SECONDS).intValue());

        long deadline = System.currentTimeMillis() + 3000;
        while (ThreadPool.taskSerialMap.containsKey(taskId) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertFalse(ThreadPool.taskSerialMap.containsKey(taskId));
        assertFalse(ThreadPool.taskSerialCountMap.containsKey(taskId));
    }

    @Test
    void shutdownNowShouldClearQueuedAsyncTaskMap() throws Exception {
        pool = new ThreadPool(1, 16, null, "ASYNC-DRAIN");
        CountDownLatch blocking = new CountDownLatch(1);
        CompletableFuture<Void> running = pool.runAsync(() -> {
            try {
                blocking.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        List<CompletableFuture<Void>> queued = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            queued.add(pool.runAsync(() -> {
            }));
        }

        Thread.sleep(200);
        pool.shutdownNow();
        blocking.countDown();
        try {
            running.get(2, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }

        long deadline = System.currentTimeMillis() + 3000;
        while (!pool.taskMap.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(pool.taskMap.isEmpty(), "taskMap should be empty after shutdownNow queue drain");
    }

    @Test
    void cancelBeforeRunShouldWakeGet() {
        timer = new WheelTimer(Tasks.executor());
        ScheduledFuture<?> future = timer.schedule(() -> {
        }, 5, TimeUnit.SECONDS);

        assertTrue(future.cancel(true));
        assertThrows(CancellationException.class, future::get);
    }

    @Test
    void scheduleAtFixedRateShouldStopAfterException() throws Exception {
        timer = new WheelTimer(Tasks.executor());
        AtomicInteger counter = new AtomicInteger();
        ScheduledFuture<?> future = timer.scheduleAtFixedRate(() -> {
            if (counter.incrementAndGet() == 1) {
                throw new IllegalStateException("boom");
            }
        }, 0, 100, TimeUnit.MILLISECONDS);

        ExecutionException error = assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
        assertTrue(error.getCause() instanceof IllegalStateException);

        Thread.sleep(300);
        assertEquals(1, counter.get());
    }

    @Test
    void schedulePeriodicShouldValidateArguments() {
        timer = new WheelTimer(Tasks.executor());
        assertThrows(IllegalArgumentException.class, () -> timer.scheduleAtFixedRate(() -> {
        }, 0, 0, TimeUnit.MILLISECONDS));
        assertThrows(IllegalArgumentException.class, () -> timer.scheduleWithFixedDelay(() -> {
        }, 0, 0, TimeUnit.MILLISECONDS));
    }

    @Test
    void shutdownShouldRejectNewTasks() {
        timer = new WheelTimer(Tasks.executor());
        timer.shutdown();

        assertThrows(RejectedExecutionException.class, () -> timer.schedule(() -> {
        }, 0, TimeUnit.MILLISECONDS));
        assertThrows(RejectedExecutionException.class, () -> timer.execute(() -> {
        }));
    }
}
