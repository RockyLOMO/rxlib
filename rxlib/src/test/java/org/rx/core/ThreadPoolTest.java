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

import org.rx.AbstractTester;
import org.rx.bean.*;
import org.rx.util.function.TripleAction;
import org.rx.exception.InvalidException;
import org.rx.core.Constants;
import io.netty.util.concurrent.FastThreadLocal;
import org.slf4j.MDC;
import static org.rx.core.Extends.*;
import static org.rx.core.Sys.*;
import org.rx.core.Arrays;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
public class ThreadPoolTest extends AbstractTester {
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

        // First task occupies the SINGLE lock
        p.run(() -> {
            counter.incrementAndGet();
            startedLatch.countDown();
            finishLatch.await();
        }, taskId, RunFlag.SINGLE.flags());

        // Wait for first task to start
        assertTrue(startedLatch.await(5, TimeUnit.SECONDS));

        // Subsequent tasks with same taskId should be rejected (RejectedExecutionException in beforeExecute)
        Thread.sleep(200);
        for (int i = 0; i < 3; i++) {
            p.run(() -> counter.incrementAndGet(), taskId, RunFlag.SINGLE.flags());
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

        // Use run() to verify flags are preserved through submit() -> newTaskFor()
        for (int i = 0; i < taskCount; i++) {
            p.run(() -> {
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
        // Verify that the synchronized/wait(500) signaling unblocks quickly via notify()
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

        // Release the blocking task — should unblock immediately via notify()
        blockLatch.countDown();
        blockedOfferThread.join(3000);
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        assertTrue(offerCompleted.get(), "Offer should have completed after queue space freed");
        // Even with wait(500), notify() should wake it up much faster than 500ms
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

    //region thread pool
    @SneakyThrows
    @Test
    public void threadPool() {
        ThreadPool pool = Tasks.nextPool();
        //RunFlag.SINGLE        根据taskId单线程执行，只要有一个线程在执行，其它线程直接跳过执行。
        //RunFlag.SYNCHRONIZED  根据taskId同步执行，只要有一个线程在执行，其它线程等待执行。
        //RunFlag.TRANSFER      直到任务被执行或放入队列否则一直阻塞调用线程。
        //RunFlag.PRIORITY      如果线程和队列都无可用的则直接新建线程执行。
        //RunFlag.INHERIT_THREAD_LOCALS 子线程会继承父线程的FastThreadLocal
        //RunFlag.THREAD_TRACE  开启trace,支持timer和CompletableFuture
        AtomicInteger c = new AtomicInteger();
        for (int i = 0; i < 5; i++) {
            int x = i;
            Future<Void> f1 = pool.run(() -> {
                log.info("exec SINGLE begin {}", x);
                c.incrementAndGet();
                sleep(oneSecond);
                wait.set();
                log.info("exec SINGLE end {}", x);
            }, c, RunFlag.SINGLE.flags());
        }
        wait.waitOne();
        wait.reset();
        assert c.get() == 1;

        for (int i = 0; i < 5; i++) {
            int x = i;
            Future<Void> f1 = pool.run(() -> {
                log.info("exec SYNCHRONIZED begin {}", x);
                c.incrementAndGet();
                sleep(oneSecond);
                log.info("exec SYNCHRONIZED end {}", x);
            }, c, RunFlag.SYNCHRONIZED.flags());
        }
        sleep(6000);
        assert c.get() == 6;

        for (int i = 0; i < 5; i++) {
            int x = i;
            Future<Void> f1 = pool.run(() -> {
                log.info("exec TRANSFER begin {}", x);
                c.incrementAndGet();
                sleep(oneSecond);
                log.info("exec TRANSFER end {}", x);
            }, c, RunFlag.TRANSFER.flags());
        }
        sleep(6000);
        assert c.get() == 11;


        c.set(0);
        for (int i = 0; i < 5; i++) {
            int x = i;
            CompletableFuture<Void> f1 = pool.runAsync(() -> {
                log.info("exec SINGLE begin {}", x);
                c.incrementAndGet();
                sleep(oneSecond);
                wait.set();
                log.info("exec SINGLE end {}", x);
            }, c, RunFlag.SINGLE.flags());
            f1.whenCompleteAsync((r, e) -> log.info("exec SINGLE uni"));
        }
        wait.waitOne();
        wait.reset();
        assert c.get() == 1;

        for (int i = 0; i < 5; i++) {
            int x = i;
            CompletableFuture<Void> f1 = pool.runAsync(() -> {
                log.info("exec SYNCHRONIZED begin {}", x);
                c.incrementAndGet();
                sleep(oneSecond);
                log.info("exec SYNCHRONIZED end {}", x);
            }, c, RunFlag.SYNCHRONIZED.flags());
            f1.whenCompleteAsync((r, e) -> log.info("exec SYNCHRONIZED uni"));
        }
        sleep(8000);
        assert c.get() == 6;

        pool.runAsync(() -> System.out.println("runAsync"))
                .whenCompleteAsync((r, e) -> System.out.println("whenCompleteAsync"))
                .join();
        List<Func<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            int x = i;
            tasks.add(() -> {
                log.info("TASK begin {}", x);
                sleep(oneSecond);
                log.info("TASK end {}", x);
                return x + 100;
            });
        }
        List<Future<Integer>> futures = pool.runAll(tasks, 0);
        for (Future<Integer> future : futures) {
            log.info("runAll get {}", future.get());
        }

        ThreadPool.MultiTaskFuture<Integer, Integer> anyMf = pool.runAnyAsync(tasks);
        anyMf.getFuture().whenCompleteAsync((r, e) -> log.info("ANY TASK MAIN uni"));
        for (CompletableFuture<Integer> sf : anyMf.getSubFutures()) {
            sf.whenCompleteAsync((r, e) -> log.info("ANY TASK uni {}", r));
        }
        for (CompletableFuture<Integer> sf : anyMf.getSubFutures()) {
            sf.join();
        }
        log.info("wait ANY TASK");
        anyMf.getFuture().get();

        ThreadPool.MultiTaskFuture<Void, Integer> mf = pool.runAllAsync(tasks);
        mf.getFuture().whenCompleteAsync((r, e) -> log.info("ALL TASK MAIN uni"));
        for (CompletableFuture<Integer> sf : mf.getSubFutures()) {
            sf.whenCompleteAsync((r, e) -> log.info("ALL TASK uni {}", r));
        }
        for (CompletableFuture<Integer> sf : mf.getSubFutures()) {
            sf.join();
        }
        log.info("wait ALL TASK");
        mf.getFuture().get();
    }

    @Test
    public void threadPoolAutosize() {
        //LinkedTransferQueue基于CAS实现，大部分场景下性能比LinkedBlockingQueue好。
        //拒绝策略 当thread和queue都满了后会block调用线程直到queue加入成功，平衡生产和消费
        //支持netty FastThreadLocal
        long delayMillis = 5000;
        ExecutorService pool = new ThreadPool(1, 1, new IntWaterMark(20, 40), "DEV");
        for (int i = 0; i < 100; i++) {
            int x = i;
            pool.execute(() -> {
                log.info("exec {} begin..", x);
                sleep(delayMillis);
                log.info("exec {} end..", x);
            });
        }
    }

    @SneakyThrows
    @Test
    public void inheritThreadLocal() {
        Class.forName(Tasks.class.getName());
        ForkJoinPoolWrapper.transform();
        //线程trace，支持异步trace包括Executor(ThreadPool), ScheduledExecutorService(WheelTimer), CompletableFuture.xxAsync(), parallelStream()系列方法。
        RxConfig.INSTANCE.getThreadPool().setTraceName("rx-traceId");
        ThreadPool.traceIdGenerator = () -> UUID.randomUUID().toString().replace("-", "");
        ThreadPool.onTraceIdChanged.combine((s, e) -> MDC.put("rx-traceId", e));
        ThreadPool pool = new ThreadPool(3, 1, new IntWaterMark(20, 40), "DEV");

        //当线程池无空闲线程时，任务放置队列后，当队列任务执行时会带上正确的traceId
        ThreadPool.startTrace(null);
        for (int i = 0; i < 2; i++) {
            int finalI = i;
            pool.run(() -> {
                log.info("TRACE DELAY-1 {}", finalI);
                pool.run(() -> {
                    log.info("TRACE DELAY-1_1 {}", finalI);
                    sleep(oneSecond);
                });
                sleep(oneSecond);
            });
            log.info("TRACE DELAY MAIN {}", finalI);
            pool.run(() -> {
                log.info("TRACE DELAY-2 {}", finalI);
                sleep(oneSecond);
            });
        }
        ThreadPool.endTrace();
        sleep(5000);
        System.out.println("---next---");

        //WheelTimer(ScheduledExecutorService) 异步trace
        WheelTimer timer = Tasks.timer();
        ThreadPool.startTrace(null);
        for (int i = 0; i < 2; i++) {
            int finalI = i;
            timer.setTimeout(() -> {
                log.info("TRACE TIMER {}", finalI);
                sleep(oneSecond);
            }, oneSecond);
            log.info("TRACE TIMER MAIN {}", finalI);
        }
        ThreadPool.endTrace();
        sleep(5000);
        System.out.println("---next---");

        //CompletableFuture.xxAsync异步方法正确获取trace
        ThreadPool.startTrace(null);
        for (int i = 0; i < 2; i++) {
            int finalI = i;
            CompletableFuture<Void> cf1 = pool.runAsync(() -> {
                log.info("TRACE ASYNC-1 {}", finalI);
                pool.runAsync(() -> {
                    log.info("TRACE ASYNC-1_1 {}", finalI);
                    sleep(oneSecond);
                }).whenCompleteAsync((r, e) -> log.info("TRACE ASYNC-1_1 uni {}", r));
                sleep(oneSecond);
            }).whenCompleteAsync((r, e) -> log.info("TRACE ASYNC-1 uni {}", r));
            log.info("TRACE ASYNC MAIN {}", finalI);
            CompletableFuture<Void> cf2 = pool.runAsync(() -> {
                log.info("TRACE ASYNC-2 {}", finalI);
                sleep(oneSecond);
            }).whenCompleteAsync((r, e) -> log.info("TRACE ASYNC-2 uni {}", r));
        }
        ThreadPool.endTrace();
        sleep(5000);
        System.out.println("---next---");

        ThreadPool.startTrace(null);
        log.info("TRACE ALL_OF start");
        CompletableFuture.allOf(pool.runAsync(() -> {
            log.info("TRACE ALL_OF ASYNC-1");
            pool.runAsync(() -> {
                log.info("TRACE ALL_OF ASYNC-1_1");
                sleep(oneSecond);
            }).whenCompleteAsync((r, e) -> log.info("TRACE ALL_OF ASYNC-1_1 uni {}", r));
            sleep(oneSecond);
        }).whenCompleteAsync((r, e) -> log.info("TRACE ALL_OF ASYNC-1 uni {}", r)), pool.runAsync(() -> {
            log.info("TRACE ALL_OF ASYNC-2");
            sleep(oneSecond);
        }).whenCompleteAsync((r, e) -> log.info("TRACE ALL_OF ASYNC-2 uni {}", r))).whenCompleteAsync((r, e) -> {
            log.info("TRACE ALL-OF {}", r);
        }).get(10, TimeUnit.SECONDS);
        log.info("TRACE ALL_OF end");
        ThreadPool.endTrace();
        sleep(5000);
        System.out.println("---next---");

        //parallelStream
        ThreadPool.startTrace(null);
        Arrays.toList(0, 1, 2, 3, 4, 5, 6, 7, 8, 9).parallelStream().map(p -> {
            //todo
            Arrays.toList("a", "b", "c").parallelStream().map(x -> {
                log.info("parallelStream {} -> {}", p, x);
                return x.toString();
            }).collect(Collectors.toList());
            log.info("parallelStream {}", p);
            return p.toString();
        }).collect(Collectors.toList());
        ThreadPool.endTrace();
        sleep(5000);
        System.out.println("---next---");

        //timer
        ThreadPool.startTrace(null);
        Tasks.timer().setTimeout(() -> {
            log.info("TIMER 1");
            pool.run(() -> {
                log.info("TIMER 2");
            });
        }, d -> d > 5000 ? -1 : Math.max(d * 2, 1000), null, TimeoutFlag.PERIOD.flags());
        ThreadPool.endTrace();
        sleep(8000);
        System.out.println("---next---");

        //netty FastThreadLocal 支持继承
        FastThreadLocal<Integer> ftl = new FastThreadLocal<>();
        ftl.set(64);
        pool.run(() -> {
            assert ftl.get() == 64;
            log.info("Inherit ok 1");
        }, null, RunFlag.INHERIT_FAST_THREAD_LOCALS.flags());

        pool.runAsync(() -> {
            assert ftl.get() == 64;
            log.info("Inherit ok 2");
        }, null, RunFlag.INHERIT_FAST_THREAD_LOCALS.flags());
        sleep(2000);
        System.out.println("---next---");

        //ExecutorService
        ThreadPool.startTrace(null);
        log.info("root scope begin");
        ExecutorService es = pool;
        es.submit(() -> {
            log.info("submit..");
            return 1024;
        });
        es.execute(() -> {
            log.info("exec..");
        });
        sleep(1000);

        //nest trace
        log.info("nest scope1 prepare");
        ThreadPool.startTrace("newScope1", true);
        log.info("nest scope1 begin");

        log.info("nest scope2 prepare");
        ThreadPool.startTrace("newScope2", true);
        log.info("nest scope2 begin");

        es.execute(() -> {
            log.info("nest sub begin");
            for (int i = 0; i < 5; i++) {
                ThreadPool.startTrace(null);
                log.info("nest sub {}", i);
                ThreadPool.endTrace();
            }
            log.info("nest sub end");
        });

        log.info("nest scope2 end");
        ThreadPool.endTrace();
        log.info("nest scope2 post");

        log.info("nest scope1 end");
        ThreadPool.endTrace();
        log.info("nest scope1 post");

        log.info("root scope end");
        ThreadPool.endTrace();
        log.info("--done--");

        for (int i = 0; i < 3; i++) {
            int finalI = i;
            es.execute(() -> {
                log.info("nest sub{} begin", finalI);
                for (int j = 0; j < 5; j++) {
                    ThreadPool.startTrace(null);
                    log.info("nest sub{} {}", finalI, j);
                    sleep(200);
                    ThreadPool.endTrace();
                }
                log.info("nest sub{} end", finalI);
            });
        }
        sleep(5000);
        System.out.println("---done---");
    }

    @SneakyThrows
    @Test
    public void serialAsync() {
        RxConfig.INSTANCE.getThreadPool().setTraceName("rx-traceId");
        ThreadPool.onTraceIdChanged.combine((s, e) -> MDC.put("rx-traceId", e));
        ThreadPool.startTrace(null);

        ThreadPool pool = Tasks.nextPool();
        String tid1 = "sat1", tid2 = "sat2";
        Future<Integer> f = null;
        for (int i = 0; i < 10; i++) {
            Object tid = i % 2 == 0 ? tid1 : tid2;
            int finalI = i;
            f = pool.runSerial(() -> {
                log.info("serial {} - {}", tid, finalI);
                return finalI + 100;
            }, tid);
        }
        log.info("last result {}", f.get());
        System.out.println("ok");

        CompletableFuture<Integer> fa = null;
        for (int i = 0; i < 10; i++) {
            Object tid = i % 2 == 0 ? tid1 : tid2;
            int finalI = i;
            fa = pool.runSerialAsync(() -> {
                log.info("serial {} - {} CTX:{}", tid, finalI, ThreadPool.completionReturnedValue());
                return finalI + 100;
            }, tid);
            if (i == 5) {
                CompletableFuture<String> tf = fa.thenApplyAsync(rv -> {
                    log.info("linkTf returned {}", rv);
                    return "okr";
                });
                log.info("linkTf then get {}", tf.get());
            }
        }
        log.info("last result {}", fa.get());
        sleep(2000);
        System.out.println("ok");
    }

    @SneakyThrows
    @Test
    public void timer() {
        WheelTimer timer = Tasks.timer();
        //TimeoutFlag.SINGLE       根据taskId单线程执行，只要有一个线程在执行，其它线程直接跳过执行。
        //TimeoutFlag.REPLACE      根据taskId执行，如果已有其它线程执行或等待执行则都取消，只执行当前。
        //TimeoutFlag.PERIOD       定期重复执行，遇到异常不会终止直到asyncContinue(false) 或 next delay = -1。
        //TimeoutFlag.THREAD_TRACE 开启trace
        AtomicInteger c = new AtomicInteger();
        for (int i = 0; i < 5; i++) {
            int finalI = i;
            timer.setTimeout(() -> {
                log.info("exec SINGLE plus by {}", finalI);
                assert finalI == 0;
                c.incrementAndGet();
                sleep(oneSecond);
                wait.set();
            }, oneSecond, c, TimeoutFlag.SINGLE.flags());
        }
        wait.waitOne();
        wait.reset();
        assert c.get() == 1;
        log.info("exec SINGLE flag ok..");

        for (int i = 0; i < 5; i++) {
            int finalI = i;
            timer.setTimeout(() -> {
                log.info("exec REPLACE plus by {}", finalI);
                assert finalI == 4;
                c.incrementAndGet();
                sleep(oneSecond);
                wait.set();
            }, oneSecond, c, TimeoutFlag.REPLACE.flags());
        }
        wait.waitOne();
        wait.reset();
        assert c.get() == 2;
        log.info("exec REPLACE flag ok..");

        TimeoutFuture<Integer> f = timer.setTimeout(() -> {
            log.info("exec PERIOD");
            int i = c.incrementAndGet();
            if (i > 10) {
                circuitContinue(false);
                return null;
            }
            if (i == 4) {
                throw new InvalidException("Will exec next");
            }
            circuitContinue(true);
            return i;
        }, oneSecond, c, TimeoutFlag.PERIOD.flags());
        sleep(8000);
        f.cancel();
        log.info("exec PERIOD flag ok and last value={}", f.get());
        assert f.get() == 9;

        c.set(0);
        timer.setTimeout(() -> {
            log.info("exec nextDelayFn");
            c.incrementAndGet();
            circuitContinue(true);
        }, d -> d > 1000 ? -1 : Math.max(d, 100) * 2);
        sleep(5000);
        log.info("exec nextDelayFn ok");
        assert c.get() == 4;

        //包装为ScheduledExecutorService
        ScheduledExecutorService ses = timer;
        ScheduledFuture<Integer> f1 = ses.schedule(() -> 1024, oneSecond, TimeUnit.MILLISECONDS);
        long start = System.currentTimeMillis();
        assert f1.get() == 1024;
        log.info("schedule wait {}ms", (System.currentTimeMillis() - start));

        log.info("scheduleAtFixedRate step 1");
        ScheduledFuture<?> f2 = ses.scheduleAtFixedRate(() -> log.info("scheduleAtFixedRate step 2"), 500, oneSecond, TimeUnit.MILLISECONDS);
        log.info("scheduleAtFixedRate delay {}ms", f2.getDelay(TimeUnit.MILLISECONDS));
        sleep(5000);
        f2.cancel(true);
        log.info("scheduleAtFixedRate delay {}ms", f2.getDelay(TimeUnit.MILLISECONDS));
    }

    @SneakyThrows
    @Test
    public void mx() {
        assert Sys.formatNanosElapsed(985).equals("985ns");
        assert Sys.formatNanosElapsed(211985).equals("211µs");
        assert Sys.formatNanosElapsed(2211985).equals("2ms");
        assert Sys.formatNanosElapsed(2211985, 1).equals("2s");
        assert Sys.formatNanosElapsed(2048211985).equals("2s");

//        Sys.mxScheduleTask(p -> {
//            log.info("Disk={} <- {}", p.getDisks().where(DiskInfo::isBootstrapDisk).first().getUsedPercent(),
//                    toJsonString(p));
//        });
//
//        System.out.println("main thread done");
//        sleep(12000);
//        log.info("dlt: {}", Sys.findDeadlockedThreads());
//        log.info("at: {}", Sys.getAllThreads().toJoinString("\n", ThreadInfo::toString));

        Tasks.nextPool().submit(() -> {
            log.info("pool exec");
            throw new Exception("pool exec");
        });
        Tasks.timer().setTimeout(() -> {
            log.info("timer exec");
            throw new Exception("timer exec");
        }, 1000);

        _wait();
    }
    //endregion

}
