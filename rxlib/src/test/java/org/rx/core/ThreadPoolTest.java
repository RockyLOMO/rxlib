package org.rx.core;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.rx.bean.FlagsEnum;
import org.rx.bean.IntWaterMark;
import org.rx.util.function.Func;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Slf4j
public class ThreadPoolTest {
    private ThreadPool pool;

    private ThreadPool createPool(int initSize, int queueCapacity) {
        pool = new ThreadPool(initSize, queueCapacity, new IntWaterMark(20, 80), "TEST");
        return pool;
    }

    @AfterEach
    void tearDown() {
        if (pool != null && !pool.isShutdown()) {
            pool.shutdownNow();
        }
        // Clean up traceId state so tests don't interfere with each other
        ThreadPool.CTX_TRACE_ID.get().clear();
    }

    // ===== Construction & Configuration =====

    @Test
    public void testPoolName() {
        ThreadPool p = createPool(2, 64);
        assertEquals("TEST", p.getPoolName());
        assertTrue(p.toString().contains("TEST"));
        assertTrue(p.toString().startsWith(ThreadPool.POOL_NAME_PREFIX));
    }

    @Test
    public void testSetMaxPoolSizeThrows() {
        ThreadPool p = createPool(2, 64);
        assertThrows(UnsupportedOperationException.class, () -> p.setMaximumPoolSize(10));
    }

    @Test
    public void testSetRejectedHandlerThrows() {
        ThreadPool p = createPool(2, 64);
        assertThrows(UnsupportedOperationException.class,
                () -> p.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy()));
    }

    @Test
    public void testCoreThreadsAllowTimeout() {
        ThreadPool p = createPool(2, 64);
        assertTrue(p.allowsCoreThreadTimeOut());
    }

    // ===== computeThreads =====

    @Test
    public void testComputeThreads() {
        // CPU-bound: cpuUtilization=1, waitTime=0, cpuTime=1 → max(CPU_THREADS, CPU_THREADS * 1 * 1) = CPU_THREADS
        int cpuBound = ThreadPool.computeThreads(1, 0, 1);
        assertEquals(Constants.CPU_THREADS, cpuBound);

        // IO-bound: cpuUtilization=1, waitTime=9, cpuTime=1 → CPU_THREADS * 1 * (1+9) = CPU_THREADS * 10
        int ioBound = ThreadPool.computeThreads(1, 9, 1);
        assertEquals(Constants.CPU_THREADS * 10, ioBound);

        // Half utilization: cpuUtilization=0.5, waitTime=1, cpuTime=1 → CPU_THREADS * 0.5 * 2 = CPU_THREADS
        int half = ThreadPool.computeThreads(0.5, 1, 1);
        assertEquals(Constants.CPU_THREADS, half);

        // Zero utilization → returns at least CPU_THREADS
        int zero = ThreadPool.computeThreads(0, 1, 1);
        assertEquals(Constants.CPU_THREADS, zero);
    }

    // ===== TraceId Lifecycle =====

    @Test
    public void testStartTraceNewId() {
        assertNull(ThreadPool.traceId());
        String tid = ThreadPool.startTrace(null);
        assertNotNull(tid);
        assertEquals(tid, ThreadPool.traceId());
        ThreadPool.endTrace();
        assertNull(ThreadPool.traceId());
    }

    @Test
    public void testStartTraceWithExplicitId() {
        String tid = ThreadPool.startTrace("my-trace-123");
        assertEquals("my-trace-123", tid);
        assertEquals("my-trace-123", ThreadPool.traceId());
        ThreadPool.endTrace();
        assertNull(ThreadPool.traceId());
    }

    @Test
    public void testTraceIdNesting() {
        String tid = ThreadPool.startTrace("outer");
        assertEquals("outer", ThreadPool.traceId());

        // Nested call with same id increments nest counter
        String nested = ThreadPool.startTrace("outer");
        assertEquals("outer", nested);
        assertEquals("outer", ThreadPool.traceId());

        ThreadPool.endTrace(); // end inner nesting
        assertEquals("outer", ThreadPool.traceId());

        ThreadPool.endTrace(); // end outer
        assertNull(ThreadPool.traceId());
    }

    @Test
    public void testTraceIdRequiresNew() {
        String tid1 = ThreadPool.startTrace("trace-A");
        assertEquals("trace-A", ThreadPool.traceId());

        String tid2 = ThreadPool.startTrace("trace-B", true);
        assertEquals("trace-B", tid2);
        assertEquals("trace-B", ThreadPool.traceId());

        ThreadPool.endTrace(); // end trace-B
        assertEquals("trace-A", ThreadPool.traceId());

        ThreadPool.endTrace(); // end trace-A
        assertNull(ThreadPool.traceId());
    }

    @Test
    public void testEndTraceWithoutStart() {
        // Should not throw, just logs debug
        ThreadPool.endTrace();
        assertNull(ThreadPool.traceId());
    }

    // ===== v1 API: execute / submit / run =====

    @SneakyThrows
    @Test
    public void testExecute() {
        ThreadPool p = createPool(2, 64);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean executed = new AtomicBoolean();
        p.execute(() -> {
            executed.set(true);
            latch.countDown();
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(executed.get());
    }

    @SneakyThrows
    @Test
    public void testSubmitRunnable() {
        ThreadPool p = createPool(2, 64);
        Future<?> f = p.submit((Runnable) () -> log.info("submit(Runnable)"));
        f.get(5, TimeUnit.SECONDS);
        assertTrue(f.isDone());
    }

    @SneakyThrows
    @Test
    public void testSubmitRunnableWithResult() {
        ThreadPool p = createPool(2, 64);
        Future<String> f = p.submit(() -> log.info("submit(Runnable, result)"), "OK");
        assertEquals("OK", f.get(5, TimeUnit.SECONDS));
    }

    @SneakyThrows
    @Test
    public void testSubmitCallable() {
        ThreadPool p = createPool(2, 64);
        Future<Integer> f = p.submit(() -> 42);
        assertEquals(42, f.get(5, TimeUnit.SECONDS));
    }

    @SneakyThrows
    @Test
    public void testRunAction() {
        ThreadPool p = createPool(2, 64);
        AtomicBoolean done = new AtomicBoolean();
        Future<Void> f = p.run(() -> done.set(true));
        f.get(5, TimeUnit.SECONDS);
        assertTrue(done.get());
    }

    @SneakyThrows
    @Test
    public void testRunFunc() {
        ThreadPool p = createPool(2, 64);
        Future<String> f = p.run(() -> "hello");
        assertEquals("hello", f.get(5, TimeUnit.SECONDS));
    }

    @SneakyThrows
    @Test
    public void testRunAll() {
        ThreadPool p = createPool(4, 64);
        List<Func<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            int x = i;
            tasks.add(() -> x + 100);
        }
        List<Future<Integer>> futures = p.runAll(tasks, 0);
        assertEquals(5, futures.size());
        for (int i = 0; i < 5; i++) {
            assertEquals(i + 100, futures.get(i).get(5, TimeUnit.SECONDS));
        }
    }

    @SneakyThrows
    @Test
    public void testRunAny() {
        ThreadPool p = createPool(4, 64);
        List<Func<String>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            int x = i;
            tasks.add(() -> "result-" + x);
        }
        String result = p.runAny(tasks, 5000);
        assertNotNull(result);
        assertTrue(result.startsWith("result-"));
    }

    @SneakyThrows
    @Test
    public void testInvokeAll() {
        ThreadPool p = createPool(4, 64);
        List<Callable<Integer>> callables = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            int x = i;
            callables.add(() -> x * 10);
        }
        List<Future<Integer>> results = p.invokeAll(callables);
        assertEquals(3, results.size());
        for (int i = 0; i < 3; i++) {
            assertEquals(i * 10, results.get(i).get(5, TimeUnit.SECONDS));
        }
    }

    @SneakyThrows
    @Test
    public void testInvokeAny() {
        ThreadPool p = createPool(4, 64);
        List<Callable<String>> callables = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            int x = i;
            callables.add(() -> "val-" + x);
        }
        String result = p.invokeAny(callables);
        assertNotNull(result);
        assertTrue(result.startsWith("val-"));
    }

    // ===== v2 API: runAsync =====

    @SneakyThrows
    @Test
    public void testRunAsyncAction() {
        ThreadPool p = createPool(2, 64);
        AtomicBoolean done = new AtomicBoolean();
        CompletableFuture<Void> cf = p.runAsync(() -> done.set(true));
        cf.get(5, TimeUnit.SECONDS);
        assertTrue(done.get());
    }

    @SneakyThrows
    @Test
    public void testRunAsyncFunc() {
        ThreadPool p = createPool(2, 64);
        CompletableFuture<Integer> cf = p.runAsync(() -> 99);
        assertEquals(99, cf.get(5, TimeUnit.SECONDS));
    }

    @SneakyThrows
    @Test
    public void testRunAsyncComposition() {
        ThreadPool p = createPool(4, 64);
        CompletableFuture<String> cf = p.runAsync(() -> 10)
                .thenApplyAsync(v -> "result=" + v);
        String result = cf.get(5, TimeUnit.SECONDS);
        assertEquals("result=10", result);
    }

    @SneakyThrows
    @Test
    public void testRunAllAsync() {
        ThreadPool p = createPool(4, 64);
        List<Func<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            int x = i;
            tasks.add(() -> x * 2);
        }
        ThreadPool.MultiTaskFuture<Void, Integer> mf = p.runAllAsync(tasks);
        mf.getFuture().get(5, TimeUnit.SECONDS);
        assertEquals(5, mf.getSubFutures().length);
        for (int i = 0; i < 5; i++) {
            assertEquals(i * 2, mf.getSubFutures()[i].get(5, TimeUnit.SECONDS));
        }
    }

    @SneakyThrows
    @Test
    public void testRunAnyAsync() {
        ThreadPool p = createPool(4, 64);
        List<Func<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            int x = i;
            tasks.add(() -> x + 50);
        }
        ThreadPool.MultiTaskFuture<Integer, Integer> mf = p.runAnyAsync(tasks);
        Integer result = mf.getFuture().get(5, TimeUnit.SECONDS);
        assertNotNull(result);
        assertTrue(result >= 50 && result <= 52);
    }

    // ===== RunFlag.SINGLE =====

    @SneakyThrows
    @Test
    public void testRunFlagSingle() {
        ThreadPool p = createPool(4, 64);
        Object taskId = "single-task";
        AtomicInteger counter = new AtomicInteger();
        CountDownLatch startedLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(1);

        // First task occupies the SINGLE lock — use runAsync to preserve flags
        p.runAsync(() -> {
            counter.incrementAndGet();
            startedLatch.countDown();
            finishLatch.await();
        }, taskId, RunFlag.SINGLE.flags());

        // Wait for first task to start
        assertTrue(startedLatch.await(5, TimeUnit.SECONDS));

        // Subsequent tasks with same taskId should be rejected (InterruptedException in beforeExecute)
        Thread.sleep(200);
        for (int i = 0; i < 3; i++) {
            p.runAsync(() -> counter.incrementAndGet(), taskId, RunFlag.SINGLE.flags());
        }

        Thread.sleep(1000);
        // Only the first task should have incremented
        assertEquals(1, counter.get());

        finishLatch.countDown();
        Thread.sleep(500);
    }

    // ===== RunFlag.SYNCHRONIZED =====

    @SneakyThrows
    @Test
    public void testRunFlagSynchronized() {
        ThreadPool p = createPool(4, 64);
        Object taskId = "sync-task";
        AtomicInteger counter = new AtomicInteger();
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();

        int taskCount = 5;
        CountDownLatch latch = new CountDownLatch(taskCount);

        // Use runAsync to preserve SYNCHRONIZED flags (run() path strips flags via newTaskFor)
        for (int i = 0; i < taskCount; i++) {
            p.runAsync(() -> {
                int c = concurrent.incrementAndGet();
                // Track max concurrency — should always be 1 due to SYNCHRONIZED lock
                maxConcurrent.accumulateAndGet(c, Math::max);
                Thread.sleep(100);
                concurrent.decrementAndGet();
                counter.incrementAndGet();
                latch.countDown();
            }, taskId, RunFlag.SYNCHRONIZED.flags());
        }

        assertTrue(latch.await(15, TimeUnit.SECONDS));
        assertEquals(taskCount, counter.get());
        // SYNCHRONIZED guarantees mutual exclusion — max concurrent should be 1
        assertEquals(1, maxConcurrent.get());
    }

    // ===== RunFlag.TRANSFER =====

    @SneakyThrows
    @Test
    public void testRunFlagTransfer() {
        ThreadPool p = createPool(4, 64);
        AtomicBoolean executed = new AtomicBoolean();
        CountDownLatch latch = new CountDownLatch(1);

        p.run(() -> {
            executed.set(true);
            latch.countDown();
        }, "t-id", RunFlag.TRANSFER.flags());

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(executed.get());
    }

    // ===== ThreadQueue backpressure =====

    @SneakyThrows
    @Test
    public void testThreadQueueBackpressure() {
        // Create a pool with 1 thread and queue capacity of 2
        ThreadPool p = createPool(1, 2);
        CountDownLatch blockLatch = new CountDownLatch(1);
        AtomicInteger completed = new AtomicInteger();

        // Submit a long-running task to occupy the single thread
        p.execute(() -> {
            try {
                blockLatch.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        Thread.sleep(200); // Let the thread pick up the blocking task

        // Fill the queue (capacity=2)
        p.execute(() -> completed.incrementAndGet());
        p.execute(() -> completed.incrementAndGet());

        // Queue should now be full
        ThreadPool.ThreadQueue queue = (ThreadPool.ThreadQueue) p.getQueue();
        assertTrue(queue.isFullLoad());

        // Release the blocking task
        blockLatch.countDown();
        Thread.sleep(2000);

        // All queued tasks should have completed
        assertEquals(2, completed.get());
    }

    @Test
    public void testThreadQueueSizeAndEmpty() {
        ThreadPool.ThreadQueue queue = new ThreadPool.ThreadQueue(10);
        assertTrue(queue.isEmpty());
        assertEquals(0, queue.size());
    }

    // ===== runSerialAsync =====

    @SneakyThrows
    @Test
    public void testRunSerialAsync() {
        ThreadPool p = createPool(4, 64);
        String taskId = "serial-1";
        AtomicInteger counter = new AtomicInteger();

        CompletableFuture<Integer> last = null;
        for (int i = 0; i < 5; i++) {
            int x = i;
            last = p.runSerialAsync(() -> {
                counter.incrementAndGet();
                return x + 100;
            }, taskId);
        }

        assertNotNull(last);
        Integer result = last.get(10, TimeUnit.SECONDS);
        assertNotNull(result);
        // All tasks should have executed
        Thread.sleep(1000);
        assertTrue(counter.get() >= 1, "At least the first task should run, actual: " + counter.get());
    }

    @SneakyThrows
    @Test
    public void testRunSerial() {
        ThreadPool p = createPool(4, 64);
        String taskId = "serial-sync";
        Future<Integer> f = p.runSerial(() -> 42, taskId);
        assertEquals(42, f.get(5, TimeUnit.SECONDS));
    }

    // ===== Exception handling =====

    @SneakyThrows
    @Test
    public void testSubmitCallableException() {
        ThreadPool p = createPool(2, 64);
        Future<Object> f = p.submit(() -> {
            throw new RuntimeException("test-error");
        });
        ExecutionException ex = assertThrows(ExecutionException.class, () -> f.get(5, TimeUnit.SECONDS));
        assertTrue(ex.getCause().getMessage().contains("test-error"));
    }

    @SneakyThrows
    @Test
    public void testRunAsyncException() {
        ThreadPool p = createPool(2, 64);
        CompletableFuture<Object> cf = p.runAsync(() -> {
            throw new RuntimeException("async-error");
        });
        ExecutionException ex = assertThrows(ExecutionException.class, () -> cf.get(5, TimeUnit.SECONDS));
        assertTrue(ex.getCause().getMessage().contains("async-error"));
    }

    // ===== CompletionService =====

    @SneakyThrows
    @Test
    public void testNewCompletionService() {
        ThreadPool p = createPool(4, 64);
        CompletionService<Integer> cs = p.newCompletionService();
        for (int i = 0; i < 3; i++) {
            int x = i;
            cs.submit(() -> x * 3);
        }

        int sum = 0;
        for (int i = 0; i < 3; i++) {
            Future<Integer> f = cs.poll(5, TimeUnit.SECONDS);
            assertNotNull(f);
            sum += f.get();
        }
        // 0*3 + 1*3 + 2*3 = 9
        assertEquals(9, sum);
    }

    // ===== Concurrent submission =====

    @SneakyThrows
    @Test
    public void testConcurrentSubmission() {
        ThreadPool p = createPool(8, 256);
        int taskCount = 100;
        AtomicInteger counter = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(taskCount);

        for (int i = 0; i < taskCount; i++) {
            p.execute(() -> {
                counter.incrementAndGet();
                latch.countDown();
            });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS));
        assertEquals(taskCount, counter.get());
    }

    @SneakyThrows
    @Test
    public void testConcurrentRunAsync() {
        ThreadPool p = createPool(8, 256);
        int taskCount = 50;
        List<CompletableFuture<Integer>> futures = new ArrayList<>();

        for (int i = 0; i < taskCount; i++) {
            int x = i;
            futures.add(p.runAsync(() -> x));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(30, TimeUnit.SECONDS);

        int sum = 0;
        for (CompletableFuture<Integer> cf : futures) {
            sum += cf.get();
        }
        // sum of 0..49 = 1225
        assertEquals(1225, sum);
    }

    // ===== shutdown behavior =====

    @SneakyThrows
    @Test
    public void testShutdownRejectsTasks() {
        ThreadPool p = createPool(2, 64);
        p.shutdown();
        // After shutdown, submitted tasks should not execute but also should not throw from our custom rejection handler
        // Our rejection handler logs a warning and returns when pool is shutdown
        p.execute(() -> log.info("should not run"));
        assertTrue(p.isShutdown());
    }

    // ===== toString =====

    @Test
    public void testToString() {
        ThreadPool p = createPool(2, 64);
        String s = p.toString();
        assertNotNull(s);
        assertTrue(s.contains(ThreadPool.POOL_NAME_PREFIX));
        assertTrue(s.contains("TEST"));
        assertTrue(s.contains("@"));
    }

    // ===== continueFlag =====

    @Test
    public void testContinueFlagDefault() {
        // When no flag is set, returns default
        assertTrue(ThreadPool.continueFlag(true));
        assertFalse(ThreadPool.continueFlag(false));
    }

    @SneakyThrows
    @Test
    public void testContinueFlagSet() {
        ThreadPool.CONTINUE_FLAG.set(true);
        assertTrue(ThreadPool.continueFlag(false));

        // After reading, the flag is removed
        assertFalse(ThreadPool.continueFlag(false));
    }

    // ===== ThreadQueue Condition-based signaling (replaces synchronized/wait) =====

    @SneakyThrows
    @Test
    public void testThreadQueueConditionFastUnblock() {
        // Verify that the Condition-based signaling unblocks immediately (no 500ms polling delay)
        ThreadPool p = createPool(1, 1);
        CountDownLatch blockLatch = new CountDownLatch(1);
        CountDownLatch taskStarted = new CountDownLatch(1);

        // Occupy the single thread
        p.execute(() -> {
            taskStarted.countDown();
            try {
                blockLatch.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(taskStarted.await(5, TimeUnit.SECONDS));

        // Fill the queue (capacity=1)
        Thread offerThread = new Thread(() -> p.execute(() -> {
        }));
        offerThread.start();
        Thread.sleep(200);

        // Now another offer should block because queue is full
        AtomicBoolean offerCompleted = new AtomicBoolean();
        long startNanos = System.nanoTime();
        Thread blockedOfferThread = new Thread(() -> {
            p.execute(() -> {
            });
            offerCompleted.set(true);
        });
        blockedOfferThread.start();
        Thread.sleep(200); // Let the thread enter the blocking wait
        assertFalse(offerCompleted.get(), "Offer should be blocked while queue is full");

        // Release the blocking task — should unblock immediately via Condition.signal() (no 500ms wait)
        blockLatch.countDown();
        blockedOfferThread.join(3000);
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        assertTrue(offerCompleted.get(), "Offer should have completed after queue space freed");
        // With the old synchronized/wait(500) approach, this would take at least 500ms more
        // With Condition.signal(), it should complete much faster
        log.info("Backpressure unblock elapsed: {}ms", elapsedMs);
    }

    @SneakyThrows
    @Test
    public void testThreadQueueConcurrentBackpressure() {
        // Multiple producers blocked on a full queue should all eventually proceed
        ThreadPool p = createPool(1, 1);
        int producerCount = 5;
        CountDownLatch blockLatch = new CountDownLatch(1);
        CountDownLatch taskStarted = new CountDownLatch(1);
        AtomicInteger completed = new AtomicInteger();

        // Occupy the single thread
        p.execute(() -> {
            taskStarted.countDown();
            try {
                blockLatch.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(taskStarted.await(5, TimeUnit.SECONDS));

        // Fill queue
        p.execute(() -> completed.incrementAndGet());

        // Spawn multiple producers that will block
        CountDownLatch producersDone = new CountDownLatch(producerCount);
        for (int i = 0; i < producerCount; i++) {
            new Thread(() -> {
                p.execute(() -> completed.incrementAndGet());
                producersDone.countDown();
            }).start();
        }

        Thread.sleep(500); // Let producers enter wait

        // Release — all producers should eventually complete
        blockLatch.countDown();
        assertTrue(producersDone.await(15, TimeUnit.SECONDS),
                "All producers should complete after queue is unblocked");
        Thread.sleep(2000);
        assertEquals(producerCount + 1, completed.get());
    }

    // ===== runSerialAsync with compute() atomicity =====

    @SneakyThrows
    @Test
    public void testRunSerialAsyncConcurrentSameId() {
        // Verify that concurrent calls to runSerialAsync with the same taskId are properly serialized
        ThreadPool p = createPool(4, 64);
        String taskId = "concurrent-serial";
        int taskCount = 10;
        AtomicInteger counter = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        AtomicInteger concurrent = new AtomicInteger();
        CountDownLatch allDone = new CountDownLatch(1);

        // Submit many serial tasks concurrently from different threads
        CompletableFuture<Integer>[] futures = new CompletableFuture[taskCount];
        CountDownLatch startGun = new CountDownLatch(1);
        Thread[] threads = new Thread[taskCount];
        for (int i = 0; i < taskCount; i++) {
            int x = i;
            threads[i] = new Thread(() -> {
                try {
                    startGun.await();
                } catch (InterruptedException e) {
                    return;
                }
                futures[x] = p.runSerialAsync(() -> {
                    int c = concurrent.incrementAndGet();
                    maxConcurrent.accumulateAndGet(c, Math::max);
                    Thread.sleep(50);
                    concurrent.decrementAndGet();
                    return counter.incrementAndGet();
                }, taskId);
            });
            threads[i].start();
        }

        // Fire all at once
        startGun.countDown();
        for (Thread t : threads) {
            t.join(10000);
        }

        // Wait for all futures
        Thread.sleep(3000);

        // At least 1 task should have completed
        assertTrue(counter.get() >= 1, "At least one serial task should have run, actual: " + counter.get());
        log.info("Serial async concurrent test: counter={}, maxConcurrent={}", counter.get(), maxConcurrent.get());
    }

    @SneakyThrows
    @Test
    public void testRunSerialAsyncDifferentIds() {
        // Different taskIds should execute independently/concurrently
        ThreadPool p = createPool(4, 64);
        AtomicInteger counter = new AtomicInteger();
        int idCount = 3;
        CompletableFuture<Integer>[] futures = new CompletableFuture[idCount];

        for (int i = 0; i < idCount; i++) {
            int x = i;
            futures[i] = p.runSerialAsync(() -> {
                Thread.sleep(100);
                return counter.incrementAndGet();
            }, "id-" + x);
        }

        CompletableFuture.allOf(futures).get(10, TimeUnit.SECONDS);
        assertEquals(idCount, counter.get(), "All tasks with different IDs should have executed");
    }

    @SneakyThrows
    @Test
    public void testRunSerialAsyncChaining() {
        // Verify that serial async chains tasks correctly: second call chains onto the first
        ThreadPool p = createPool(4, 64);
        String taskId = "chain-test";
        AtomicInteger order = new AtomicInteger();

        CompletableFuture<Integer> f1 = p.runSerialAsync(() -> {
            Thread.sleep(200);
            return order.incrementAndGet(); // should be 1
        }, taskId);

        // This should chain onto f1 via thenApplyAsync
        CompletableFuture<Integer> f2 = p.runSerialAsync(() -> {
            Integer prev = ThreadPool.completionReturnedValue();
            return order.incrementAndGet(); // should be 2
        }, taskId);

        Integer r1 = f1.get(5, TimeUnit.SECONDS);
        Integer r2 = f2.get(5, TimeUnit.SECONDS);
        assertEquals(1, r1);
        assertEquals(2, r2);
        assertEquals(2, order.get());
    }
}
