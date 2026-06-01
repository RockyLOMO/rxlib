package org.rx.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rx.util.function.Func;

import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ThreadPoolTraceIdTest {
    private ThreadPool pool;
    private Func<String> oldTraceIdGenerator;
    private long oldSlowMethodElapsedMicros;

    @BeforeEach
    void setUp() {
        oldTraceIdGenerator = ThreadPool.traceIdGenerator;
        oldSlowMethodElapsedMicros = RxConfig.INSTANCE.getTrace().getSlowMethodElapsedMicros();
        RxConfig.INSTANCE.getTrace().setSlowMethodElapsedMicros(0);
    }

    @AfterEach
    void tearDown() {
        if (pool != null && !pool.isShutdown()) {
            pool.shutdownNow();
        }
        ThreadPool.traceIdGenerator = oldTraceIdGenerator;
        RxConfig.INSTANCE.getTrace().setSlowMethodElapsedMicros(oldSlowMethodElapsedMicros);
        ThreadPool.CTX_TRACE_ID.get().clear();
    }

    @Test
    void threadTraceUsesCapturedTraceWhenWorkerHasDifferentTrace() throws Exception {
        RxConfig.ThreadPoolConfig conf = RxConfig.INSTANCE.getThreadPool();
        AtomicReference<String> traceInTask = new AtomicReference<>();
        AtomicReference<String> workerTraceAfterTask = new AtomicReference<>();
        String staleTrace = "worker-stale-" + UUID.randomUUID();
        String capturedTrace = "captured-" + UUID.randomUUID();
        try (ThreadPoolConfigSnapshot ignored = ThreadPoolConfigSnapshot.capture()) {
            conf.setTraceName(null);
            conf.setMaxTraceDepth(5);
            pool = ThreadPool.fixed("TRACE-WORKER-CAPTURED", 1, 8);

            pool.runAsync(() -> ThreadPool.startTrace(staleTrace)).get(5, TimeUnit.SECONDS);

            conf.setTraceName("rx-traceId");
            ThreadPool.startTrace(capturedTrace);
            try {
                pool.runAsync(() -> traceInTask.set(ThreadPool.traceId())).get(5, TimeUnit.SECONDS);
                assertEquals(capturedTrace, traceInTask.get());
                assertEquals(capturedTrace, ThreadPool.traceId());
            } finally {
                ThreadPool.endTrace();
            }

            conf.setTraceName(null);
            pool.runAsync(() -> workerTraceAfterTask.set(ThreadPool.traceId())).get(5, TimeUnit.SECONDS);
            assertEquals(staleTrace, workerTraceAfterTask.get());
            pool.runAsync(() -> ThreadPool.endTrace()).get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void threadTraceGeneratesNewTraceWhenCallerHasNoTraceAndWorkerHasDifferentTrace() throws Exception {
        RxConfig.ThreadPoolConfig conf = RxConfig.INSTANCE.getThreadPool();
        AtomicReference<String> traceInTask = new AtomicReference<>();
        AtomicReference<String> workerTraceAfterTask = new AtomicReference<>();
        String staleTrace = "worker-stale-" + UUID.randomUUID();
        String generatedTrace = "generated-" + UUID.randomUUID();
        try (ThreadPoolConfigSnapshot ignored = ThreadPoolConfigSnapshot.capture()) {
            conf.setTraceName(null);
            conf.setMaxTraceDepth(5);
            ThreadPool.traceIdGenerator = () -> generatedTrace;
            pool = ThreadPool.fixed("TRACE-WORKER-GENERATED", 1, 8);

            pool.runAsync(() -> ThreadPool.startTrace(staleTrace)).get(5, TimeUnit.SECONDS);

            conf.setTraceName("rx-traceId");
            pool.runAsync(() -> traceInTask.set(ThreadPool.traceId())).get(5, TimeUnit.SECONDS);
            assertEquals(generatedTrace, traceInTask.get());
            assertNotEquals(staleTrace, traceInTask.get());
            assertNull(ThreadPool.traceId());

            conf.setTraceName(null);
            pool.runAsync(() -> workerTraceAfterTask.set(ThreadPool.traceId())).get(5, TimeUnit.SECONDS);
            assertEquals(staleTrace, workerTraceAfterTask.get());
            pool.runAsync(() -> ThreadPool.endTrace()).get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void callerRunsTraceUsesCapturedTraceAndRestoresCallerTrace() throws Exception {
        RxConfig.ThreadPoolConfig conf = RxConfig.INSTANCE.getThreadPool();
        CountDownLatch blocking = new CountDownLatch(1);
        AtomicReference<String> traceInTask = new AtomicReference<>();
        String capturedTrace = "captured-" + UUID.randomUUID();
        String callerTrace = "caller-" + UUID.randomUUID();
        try (ThreadPoolConfigSnapshot ignored = ThreadPoolConfigSnapshot.capture()) {
            conf.setTraceName(null);
            conf.setMaxTraceDepth(5);
            conf.setQueueOfferMode(ThreadPoolQueueOfferMode.CALLER_RUNS);
            conf.setQueueOfferTimeoutMillis(0);
            pool = ThreadPool.fixed("TRACE-CALLER-RUNS", 1, 1);

            CompletableFuture<Void> running = pool.runAsync(() -> {
                blocking.await(5, TimeUnit.SECONDS);
            });
            waitUntil(() -> pool.getActiveCount() == 1, 3000);
            CompletableFuture<Void> queued = pool.runAsync(() -> {
            });
            waitUntil(() -> pool.getQueue().size() == 1, 3000);

            ThreadPool.startTrace(capturedTrace);
            ThreadPool.Task<Void> task;
            try {
                task = ThreadPool.Task.adapt(new Callable<Void>() {
                    @Override
                    public Void call() {
                        traceInTask.set(ThreadPool.traceId());
                        return null;
                    }
                }, RunFlag.THREAD_TRACE.flags(), null);
            } finally {
                ThreadPool.endTrace();
            }

            ThreadPool.startTrace(callerTrace);
            try {
                pool.execute(task);
                assertEquals(capturedTrace, traceInTask.get());
                assertEquals(callerTrace, ThreadPool.traceId());
            } finally {
                ThreadPool.endTrace();
            }

            blocking.countDown();
            running.get(5, TimeUnit.SECONDS);
            queued.get(5, TimeUnit.SECONDS);
        } finally {
            blocking.countDown();
        }
    }

    private void waitUntil(Condition condition, long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (!condition.ok() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(condition.ok());
    }

    @Test
    void nativeThreadInheritsTraceIdButThreadPoolWorkerIsClean() throws Exception {
        RxConfig.ThreadPoolConfig conf = RxConfig.INSTANCE.getThreadPool();
        String traceId = "main-trace-" + UUID.randomUUID();
        AtomicReference<String> threadTrace = new AtomicReference<>();
        AtomicReference<String> poolTrace = new AtomicReference<>();
        
        try (ThreadPoolConfigSnapshot ignored = ThreadPoolConfigSnapshot.capture()) {
            conf.setTraceName("rx-traceId");
            conf.setMaxTraceDepth(5);
            pool = ThreadPool.fixed("TRACE-INHERIT", 1, 8);
            
            ThreadPool.startTrace(traceId);
            try {
                // 1. 原生 Thread 应该继承 traceId (用户期望的原生继承行为)
                Thread t = new Thread(() -> {
                    threadTrace.set(ThreadPool.traceId());
                });
                t.start();
                t.join();
                
                // 2. ThreadPool 正常继承 traceId，但是它的 worker 线程启动时应该是干净的
                pool.runAsync(() -> {
                    poolTrace.set(ThreadPool.traceId());
                }).get(5, TimeUnit.SECONDS);
                
                assertEquals(traceId, threadTrace.get(), "Native Thread MUST inherit traceId");
                assertEquals(traceId, poolTrace.get(), "ThreadPool MUST inherit traceId from task");
            } finally {
                ThreadPool.endTrace();
            }
        }
    }

    private interface Condition {
        boolean ok();
    }
}
