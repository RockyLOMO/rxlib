package org.rx.core;

import io.netty.util.concurrent.FastThreadLocal;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void runSerialAsyncShouldRejectWhenSerialCapacityExceeded() throws Exception {
        RxConfig.ThreadPoolConfig conf = RxConfig.INSTANCE.getThreadPool();
        CountDownLatch blocking = new CountDownLatch(1);
        String taskId = "serial-cap-" + UUID.randomUUID();
        try (ThreadPoolConfigSnapshot ignored = ThreadPoolConfigSnapshot.capture()) {
            conf.setSerialQueueCapacity(1);
            conf.setSerialQueueHardLimit(1);
            pool = ThreadPool.fixed("SERIAL-CAP", 1, 8);

            CompletableFuture<Void> first = pool.runSerialAsync(() -> {
                blocking.await(5, TimeUnit.SECONDS);
                return null;
            }, taskId);
            waitUntil(() -> ThreadPool.taskSerialCountMap.containsKey(taskId), 3000);

            assertThrows(RejectedExecutionException.class, () -> pool.runSerialAsync(() -> null, taskId));

            blocking.countDown();
            first.get(5, TimeUnit.SECONDS);
            waitUntil(() -> !ThreadPool.taskSerialCountMap.containsKey(taskId), 3000);
            assertFalse(ThreadPool.taskSerialMap.containsKey(taskId));
        } finally {
            blocking.countDown();
        }
    }

    @Test
    void queueOfferTimeoutRejectShouldFailFastWhenFixedPoolQueueFull() throws Exception {
        RxConfig.ThreadPoolConfig conf = RxConfig.INSTANCE.getThreadPool();
        CountDownLatch blocking = new CountDownLatch(1);
        try (ThreadPoolConfigSnapshot ignored = ThreadPoolConfigSnapshot.capture()) {
            conf.setQueueOfferMode(ThreadPoolQueueOfferMode.TIMEOUT_REJECT);
            conf.setQueueOfferTimeoutMillis(20);
            pool = ThreadPool.fixed("QUEUE-REJECT", 1, 1);

            CompletableFuture<Void> running = pool.runAsync(() -> {
                blocking.await(5, TimeUnit.SECONDS);
            });
            waitUntil(() -> pool.getActiveCount() == 1, 3000);
            pool.runAsync(() -> {
            });
            waitUntil(() -> pool.getQueue().size() == 1, 3000);

            assertThrows(RejectedExecutionException.class, () -> pool.runAsync(() -> {
            }));

            blocking.countDown();
            running.get(5, TimeUnit.SECONDS);
        } finally {
            blocking.countDown();
        }
    }

    @Test
    void queueOfferCallerRunsShouldExecuteOverflowTaskInSubmittingThread() throws Exception {
        RxConfig.ThreadPoolConfig conf = RxConfig.INSTANCE.getThreadPool();
        CountDownLatch blocking = new CountDownLatch(1);
        AtomicReference<String> callerThread = new AtomicReference<>();
        try (ThreadPoolConfigSnapshot ignored = ThreadPoolConfigSnapshot.capture()) {
            conf.setQueueOfferMode(ThreadPoolQueueOfferMode.CALLER_RUNS);
            conf.setQueueOfferTimeoutMillis(0);
            pool = ThreadPool.fixed("QUEUE-CALLER", 1, 1);

            CompletableFuture<Void> running = pool.runAsync(() -> {
                blocking.await(5, TimeUnit.SECONDS);
            });
            waitUntil(() -> pool.getActiveCount() == 1, 3000);
            pool.runAsync(() -> {
            });
            waitUntil(() -> pool.getQueue().size() == 1, 3000);

            String submitter = Thread.currentThread().getName();
            CompletableFuture<Void> overflow = pool.runAsync(() -> callerThread.set(Thread.currentThread().getName()));

            assertTrue(overflow.isDone());
            assertEquals(submitter, callerThread.get());
            assertEquals(1, pool.getQueue().size());

            blocking.countDown();
            running.get(5, TimeUnit.SECONDS);
        } finally {
            blocking.countDown();
        }
    }

    @Test
    void queueOfferCallerRunsShouldKeepSingleLifecycleAndCleanTaskMap() throws Exception {
        RxConfig.ThreadPoolConfig conf = RxConfig.INSTANCE.getThreadPool();
        CountDownLatch blocking = new CountDownLatch(1);
        AtomicInteger duplicateRuns = new AtomicInteger();
        String taskId = "caller-single-" + UUID.randomUUID();
        try (ThreadPoolConfigSnapshot ignored = ThreadPoolConfigSnapshot.capture()) {
            conf.setQueueOfferMode(ThreadPoolQueueOfferMode.CALLER_RUNS);
            conf.setQueueOfferTimeoutMillis(0);
            pool = ThreadPool.fixed("QUEUE-CALLER-SINGLE", 1, 1);

            CompletableFuture<Void> running = pool.runAsync(() -> {
                blocking.await(5, TimeUnit.SECONDS);
            }, taskId, RunFlag.SINGLE.flags());
            waitUntil(() -> ThreadPool.runningSingleTasks.contains(taskId), 3000);
            pool.runAsync(() -> {
            });
            waitUntil(() -> pool.getQueue().size() == 1, 3000);

            CompletableFuture<Void> overflow = pool.runAsync(() -> {
                duplicateRuns.incrementAndGet();
            }, taskId, RunFlag.SINGLE.flags());

            assertTrue(overflow.isDone());
            assertEquals(0, duplicateRuns.get());
            assertEquals(1L, pool.singleSkipCount.sum());
            assertTrue(ThreadPool.runningSingleTasks.contains(taskId));

            blocking.countDown();
            running.get(5, TimeUnit.SECONDS);
            waitUntil(() -> !ThreadPool.runningSingleTasks.contains(taskId), 3000);
            waitUntil(() -> pool.taskMap.isEmpty(), 3000);
        } finally {
            blocking.countDown();
        }
    }

    @Test
    void queueOfferCallerRunsShouldEndThreadTraceWhenTaskFails() throws Exception {
        RxConfig.ThreadPoolConfig conf = RxConfig.INSTANCE.getThreadPool();
        CountDownLatch blocking = new CountDownLatch(1);
        AtomicReference<String> traceInTask = new AtomicReference<>();
        try (ThreadPoolConfigSnapshot ignored = ThreadPoolConfigSnapshot.capture()) {
            conf.setQueueOfferMode(ThreadPoolQueueOfferMode.CALLER_RUNS);
            conf.setQueueOfferTimeoutMillis(0);
            pool = ThreadPool.fixed("QUEUE-CALLER-TRACE", 1, 1);

            CompletableFuture<Void> running = pool.runAsync(() -> {
                blocking.await(5, TimeUnit.SECONDS);
            });
            waitUntil(() -> pool.getActiveCount() == 1, 3000);
            pool.runAsync(() -> {
            });
            waitUntil(() -> pool.getQueue().size() == 1, 3000);

            String rootTrace = ThreadPool.startTrace("caller-runs-trace-" + UUID.randomUUID());
            CompletableFuture<Void> overflow = pool.runAsync(() -> {
                traceInTask.set(ThreadPool.traceId());
                throw new IllegalStateException("boom");
            }, null, RunFlag.THREAD_TRACE.flags());

            ExecutionException error = assertThrows(ExecutionException.class, () -> overflow.get(1, TimeUnit.SECONDS));
            assertTrue(error.getCause() instanceof IllegalStateException);
            assertEquals(rootTrace, traceInTask.get());
            assertEquals(rootTrace, ThreadPool.traceId());
            ThreadPool.endTrace();
            assertNull(ThreadPool.traceId());

            blocking.countDown();
            running.get(5, TimeUnit.SECONDS);
            waitUntil(() -> pool.taskMap.isEmpty(), 3000);
        } finally {
            blocking.countDown();
            ThreadPool.CTX_TRACE_ID.get().clear();
        }
    }

    @Test
    void queueOfferCallerRunsShouldRestoreFastThreadLocalMap() throws Exception {
        RxConfig.ThreadPoolConfig conf = RxConfig.INSTANCE.getThreadPool();
        CountDownLatch blocking = new CountDownLatch(1);
        FastThreadLocal<String> local = new FastThreadLocal<>();
        AtomicReference<String> inherited = new AtomicReference<>();
        try (ThreadPoolConfigSnapshot ignored = ThreadPoolConfigSnapshot.capture()) {
            conf.setQueueOfferMode(ThreadPoolQueueOfferMode.CALLER_RUNS);
            conf.setQueueOfferTimeoutMillis(0);
            pool = ThreadPool.fixed("QUEUE-CALLER-FTL", 1, 1);
            local.set("parent");

            CompletableFuture<Void> running = pool.runAsync(() -> {
                blocking.await(5, TimeUnit.SECONDS);
            });
            waitUntil(() -> pool.getActiveCount() == 1, 3000);
            pool.runAsync(() -> {
            });
            waitUntil(() -> pool.getQueue().size() == 1, 3000);

            CompletableFuture<Void> overflow = pool.runAsync(() -> {
                inherited.set(local.get());
                local.set("task");
            }, null, RunFlag.INHERIT_FAST_THREAD_LOCALS.flags());

            overflow.get(1, TimeUnit.SECONDS);
            assertEquals("parent", inherited.get());
            assertEquals("parent", local.get());

            blocking.countDown();
            running.get(5, TimeUnit.SECONDS);
            waitUntil(() -> pool.taskMap.isEmpty(), 3000);
        } finally {
            blocking.countDown();
            local.remove();
        }
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
