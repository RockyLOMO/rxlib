package org.rx.core;

import io.netty.channel.EventLoop;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.FastThreadLocal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.rx.exception.InvalidException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ThreadPoolHardeningTest {
    private final List<ThreadPool> pools = new ArrayList<>();
    private final List<WheelTimer> timers = new ArrayList<>();

    private ThreadPool track(ThreadPool pool) {
        pools.add(pool);
        return pool;
    }

    private WheelTimer track(WheelTimer timer) {
        timers.add(timer);
        return timer;
    }

    @AfterEach
    void tearDown() {
        for (WheelTimer timer : timers) {
            timer.shutdownNow();
        }
        timers.clear();
        for (ThreadPool pool : pools) {
            if (!pool.isShutdown()) {
                pool.shutdownNow();
            }
        }
        pools.clear();
        ThreadPool.taskSerialMap.clear();
        ThreadPool.runningSingleTasks.clear();
        ThreadPool.CTX_TRACE_ID.get().clear();
    }

    // ========== TP-01: Same-pool worker reentrant submit must not deadlock ==========

    @Test
    void tp01SamePoolWorkerReentrantSubmitInBlockModeShouldFailFastNotDeadlock() throws Exception {
        RxConfig.ThreadPoolConfig conf = RxConfig.INSTANCE.getThreadPool();
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch queueFilled = new CountDownLatch(1);
        AtomicReference<Throwable> innerError = new AtomicReference<>();
        try (ThreadPoolConfigSnapshot ignored = ThreadPoolConfigSnapshot.capture()) {
            conf.setQueueOfferMode(ThreadPoolQueueOfferMode.BLOCK);
            conf.setQueueOfferTimeoutMillis(0);
            ThreadPool pool = track(ThreadPool.fixed("TP01-FIXED", 1, 1));

            Future<?> outer = pool.submit(() -> {
                workerStarted.countDown();
                try {
                    assertTrue(queueFilled.await(5, TimeUnit.SECONDS));
                    pool.execute(() -> {
                    });
                } catch (Throwable t) {
                    innerError.set(t);
                }
            });
            assertTrue(workerStarted.await(5, TimeUnit.SECONDS));

            // Fill the single queue slot
            Future<?> queued = pool.submit(() -> {
            });
            waitUntil(() -> pool.getQueue().size() == 1, 3000);
            queueFilled.countDown();

            outer.get(3, TimeUnit.SECONDS);
            queued.get(3, TimeUnit.SECONDS);
            assertTrue(innerError.get() instanceof RejectedExecutionException,
                    "reentrant submit on saturated fixed pool should throw RejectedExecutionException, actual: " + innerError.get());
            assertTrue(((ThreadPool.ThreadQueue) pool.getQueue()).offerReentrantBypassCount.sum() >= 1L);
        }
    }

    @Test
    void tp01SamePoolWorkerReentrantSubmitInElasticPoolShouldExpandNonCoreWorker() throws Exception {
        RxConfig.ThreadPoolConfig conf = RxConfig.INSTANCE.getThreadPool();
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch queueFilled = new CountDownLatch(1);
        CountDownLatch innerRan = new CountDownLatch(1);
        try (ThreadPoolConfigSnapshot ignored = ThreadPoolConfigSnapshot.capture()) {
            conf.setQueueOfferMode(ThreadPoolQueueOfferMode.BLOCK);
            conf.setMaxPoolSize(4);
            ThreadPool pool = track(new ThreadPool(1, 1, null, "TP01-ELASTIC"));

            Future<?> outer = pool.submit(() -> {
                workerStarted.countDown();
                try {
                    assertTrue(queueFilled.await(5, TimeUnit.SECONDS));
                    Future<String> inner = pool.submit(() -> {
                        innerRan.countDown();
                        return "ok";
                    });
                    assertEquals("ok", inner.get(3, TimeUnit.SECONDS));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            assertTrue(workerStarted.await(5, TimeUnit.SECONDS));

            Future<?> queued = pool.submit(() -> {
            });
            waitUntil(() -> pool.getQueue().size() == 1, 3000);
            queueFilled.countDown();

            assertTrue(innerRan.await(3, TimeUnit.SECONDS));
            outer.get(3, TimeUnit.SECONDS);
            queued.get(3, TimeUnit.SECONDS);
        }
    }

    // ========== TP-02 / TP-04 / TP-09: SERIAL order, cancel/complete isolation, capacity & RunFlag ==========

    @Test
    void tp02CancelQueuedMiddleTaskMustNotStartSuccessorWhileHeadRunning() throws Exception {
        ThreadPool pool = track(ThreadPool.fixed("TP02-CANCEL", 4, 32));
        String taskId = "tp02-cancel-" + UUID.randomUUID();
        CountDownLatch aStarted = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        AtomicBoolean aFinished = new AtomicBoolean();
        AtomicBoolean bRan = new AtomicBoolean();
        AtomicBoolean cSawAFinished = new AtomicBoolean();
        AtomicReference<Object> cPrevValue = new AtomicReference<>("UNSET");

        CompletableFuture<String> a = pool.runSerialAsync(() -> {
            aStarted.countDown();
            assertTrue(releaseA.await(5, TimeUnit.SECONDS));
            aFinished.set(true);
            return "A-RESULT";
        }, taskId);
        assertTrue(aStarted.await(3, TimeUnit.SECONDS));

        CompletableFuture<String> b = pool.runSerialAsync(() -> {
            bRan.set(true);
            return "B-RESULT";
        }, taskId);

        CompletableFuture<String> c = pool.runSerialAsync(() -> {
            cSawAFinished.set(aFinished.get());
            cPrevValue.set(ThreadPool.completionReturnedValue());
            return "C-RESULT";
        }, taskId);

        assertTrue(b.cancel(true));
        // Even after B is cancelled, C must NOT run while A is still executing
        Thread.sleep(100);
        assertFalse(c.isDone(), "C must wait until A truly finishes");

        releaseA.countDown();
        assertEquals("A-RESULT", a.get(3, TimeUnit.SECONDS));
        assertThrows(CancellationException.class, () -> b.get(3, TimeUnit.SECONDS));
        assertEquals("C-RESULT", c.get(3, TimeUnit.SECONDS));
        assertFalse(bRan.get(), "cancelled middle task B must not execute");
        assertTrue(cSawAFinished.get(), "C must execute strictly after A finished");
        assertNull(cPrevValue.get(), "cancelled B should clear completionReturnedValue for C");
        waitUntil(() -> !ThreadPool.taskSerialMap.containsKey(taskId), 3000);
    }

    @Test
    void tp02ExternalCompleteOfRunningFutureMustNotBreakSerialMutualExclusion() throws Exception {
        ThreadPool pool = track(ThreadPool.fixed("TP02-COMPLETE", 4, 32));
        String taskId = "tp02-complete-" + UUID.randomUUID();
        CountDownLatch aStarted = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        AtomicBoolean aFinished = new AtomicBoolean();
        AtomicBoolean bSawAFinished = new AtomicBoolean();

        CompletableFuture<String> a = pool.runSerialAsync(() -> {
            aStarted.countDown();
            assertTrue(releaseA.await(5, TimeUnit.SECONDS));
            aFinished.set(true);
            return "A-REAL";
        }, taskId);
        assertTrue(aStarted.await(3, TimeUnit.SECONDS));

        CompletableFuture<String> b = pool.runSerialAsync(() -> {
            bSawAFinished.set(aFinished.get());
            return "B-REAL";
        }, taskId);

        // External caller prematurely completes A's future
        assertTrue(a.complete("FORCED"));
        Thread.sleep(100);
        assertFalse(b.isDone(), "external complete of A future must not start B before A body exits");

        releaseA.countDown();
        assertEquals("B-REAL", b.get(3, TimeUnit.SECONDS));
        assertTrue(bSawAFinished.get(), "B must run only after A's actual body finishes");
        waitUntil(() -> !ThreadPool.taskSerialMap.containsKey(taskId), 3000);
    }

    @Test
    void tp09SerialSubtasksMustHonorSingleTraceAndFastThreadLocalAndChainValue() throws Exception {
        ThreadPool pool = track(ThreadPool.fixed("TP09-FLAGS", 2, 16));
        String taskId = "tp09-flags-" + UUID.randomUUID();
        String singleId = "tp09-single-" + UUID.randomUUID();
        FastThreadLocal<String> local = new FastThreadLocal<>();
        CountDownLatch aStarted = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        AtomicReference<String> bTrace = new AtomicReference<>();
        AtomicReference<String> bLocal = new AtomicReference<>();
        AtomicReference<Object> bPrev = new AtomicReference<>();
        AtomicBoolean bAcquiredSingle = new AtomicBoolean();

        String expectedTrace = ThreadPool.startTrace("tp09-trace-" + UUID.randomUUID());
        try {
            local.set("submit-value");
            CompletableFuture<String> a = pool.runSerialAsync(() -> {
                aStarted.countDown();
                assertTrue(releaseA.await(5, TimeUnit.SECONDS));
                return "VAL-A";
            }, taskId, RunFlag.SERIAL.flags(RunFlag.THREAD_TRACE, RunFlag.INHERIT_FAST_THREAD_LOCALS));
            assertTrue(aStarted.await(3, TimeUnit.SECONDS));

            // Second task in serial chain carries SINGLE + THREAD_TRACE + INHERIT_FAST_THREAD_LOCALS
            CompletableFuture<String> b = pool.runSerialAsync(() -> {
                bTrace.set(ThreadPool.traceId());
                bLocal.set(local.get());
                bPrev.set(ThreadPool.completionReturnedValue());
                bAcquiredSingle.set(ThreadPool.runningSingleTasks.contains(taskId));
                return "VAL-B";
            }, taskId, RunFlag.SERIAL.flags(RunFlag.SINGLE, RunFlag.THREAD_TRACE, RunFlag.INHERIT_FAST_THREAD_LOCALS));

            // Modify parent FTL immediately after submit to also verify TP-12 snapshot isolation
            local.set("mutated-after-submit");

            releaseA.countDown();
            assertEquals("VAL-A", a.get(3, TimeUnit.SECONDS));
            assertEquals("VAL-B", b.get(3, TimeUnit.SECONDS));
            assertEquals(expectedTrace, bTrace.get());
            assertEquals("submit-value", bLocal.get());
            assertEquals("VAL-A", bPrev.get());
            assertTrue(bAcquiredSingle.get(), "subsequent SERIAL task must run through beforeExecute and acquire SINGLE lock");
            waitUntil(() -> !ThreadPool.runningSingleTasks.contains(taskId)
                    && !ThreadPool.runningSingleTasks.contains(singleId)
                    && !ThreadPool.taskSerialMap.containsKey(taskId), 3000);
        } finally {
            local.remove();
            ThreadPool.clearTrace();
        }
    }

    @Test
    void tp02SerialChainOnSaturatedFixedPoolShouldCompleteViaTrampolineWithoutDeadlock() throws Exception {
        RxConfig.ThreadPoolConfig conf = RxConfig.INSTANCE.getThreadPool();
        CountDownLatch aStarted = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        List<Integer> order = Collections.synchronizedList(new ArrayList<>());
        String taskId = "tp02-trampoline-" + UUID.randomUUID();
        try (ThreadPoolConfigSnapshot ignored = ThreadPoolConfigSnapshot.capture()) {
            conf.setQueueOfferMode(ThreadPoolQueueOfferMode.BLOCK);
            ThreadPool pool = track(ThreadPool.fixed("TP02-TRAMP", 1, 1));

            CompletableFuture<Integer> a = pool.runSerialAsync(() -> {
                aStarted.countDown();
                assertTrue(releaseA.await(5, TimeUnit.SECONDS));
                order.add(1);
                return 1;
            }, taskId);
            assertTrue(aStarted.await(3, TimeUnit.SECONDS));

            // Occupy the only queue slot with an unrelated task
            Future<?> blocker = pool.submit(() -> {
            });
            waitUntil(() -> pool.getQueue().size() == 1, 3000);

            CompletableFuture<Integer> b = pool.runSerialAsync(() -> {
                order.add(2);
                return 2;
            }, taskId);
            CompletableFuture<Integer> c = pool.runSerialAsync(() -> {
                order.add(3);
                return 3;
            }, taskId);

            releaseA.countDown();
            assertEquals(1, a.get(5, TimeUnit.SECONDS).intValue());
            assertEquals(2, b.get(5, TimeUnit.SECONDS).intValue());
            assertEquals(3, c.get(5, TimeUnit.SECONDS).intValue());
            blocker.get(5, TimeUnit.SECONDS);
            assertEquals(java.util.Arrays.asList(1, 2, 3), order);
            waitUntil(() -> !ThreadPool.taskSerialMap.containsKey(taskId), 3000);
        }
    }

    @Test
    void tp04SerialCapacityUnderHighConcurrencyNeverExceedsLimitAndCleansUp() throws Exception {
        RxConfig.ThreadPoolConfig conf = RxConfig.INSTANCE.getThreadPool();
        String taskId = "tp04-conc-" + UUID.randomUUID();
        int maxCap = 4;
        AtomicInteger concurrentRunning = new AtomicInteger();
        AtomicInteger maxConcurrentRunning = new AtomicInteger();
        AtomicInteger completed = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        ExecutorService submitters = Executors.newFixedThreadPool(4);
        try (ThreadPoolConfigSnapshot ignored = ThreadPoolConfigSnapshot.capture()) {
            conf.setSerialQueueCapacity(maxCap);
            conf.setSerialQueueHardLimit(maxCap);
            ThreadPool pool = track(ThreadPool.fixed("TP04-CONC", 4, 64));

            List<CompletableFuture<Integer>> accepted = Collections.synchronizedList(new ArrayList<>());
            List<Future<?>> submitFutures = new ArrayList<>();
            for (int t = 0; t < 4; t++) {
                submitFutures.add(submitters.submit(() -> {
                    for (int i = 0; i < 40; i++) {
                        try {
                            CompletableFuture<Integer> f = pool.runSerialAsync(() -> {
                                int cur = concurrentRunning.incrementAndGet();
                                maxConcurrentRunning.accumulateAndGet(cur, Math::max);
                                try {
                                    Thread.sleep(2);
                                    return completed.incrementAndGet();
                                } finally {
                                    concurrentRunning.decrementAndGet();
                                }
                            }, taskId);
                            accepted.add(f);
                        } catch (RejectedExecutionException e) {
                            rejected.incrementAndGet();
                        }
                    }
                }));
            }
            for (Future<?> sf : submitFutures) {
                sf.get(15, TimeUnit.SECONDS);
            }
            for (CompletableFuture<Integer> f : accepted) {
                f.get(15, TimeUnit.SECONDS);
            }
            assertEquals(1, maxConcurrentRunning.get(), "serial tasks must never overlap");
            assertEquals(accepted.size(), completed.get());
            assertTrue(rejected.get() > 0, "high burst should trigger capacity rejection");
            waitUntil(() -> !ThreadPool.taskSerialMap.containsKey(taskId), 3000);
        } finally {
            submitters.shutdownNow();
        }
    }

    // ========== TP-03: WheelTimer & Netty EventLoop non-blocking submit ==========

    @Test
    void tp03WheelTimerMustNotBlockWhenTargetPoolIsFullAndPeriodicRecovers() throws Exception {
        RxConfig.ThreadPoolConfig conf = RxConfig.INSTANCE.getThreadPool();
        CountDownLatch blockWorker = new CountDownLatch(1);
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch periodicRan = new CountDownLatch(2);
        try (ThreadPoolConfigSnapshot ignored = ThreadPoolConfigSnapshot.capture()) {
            conf.setQueueOfferMode(ThreadPoolQueueOfferMode.BLOCK);
            conf.setQueueOfferTimeoutMillis(0);
            ThreadPool pool = track(ThreadPool.fixed("TP03-FULL", 1, 1));
            WheelTimer timer = track(new WheelTimer(pool));

            // Saturate worker + single queue slot
            Future<?> running = pool.submit(() -> {
                workerStarted.countDown();
                try {
                    blockWorker.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(workerStarted.await(3, TimeUnit.SECONDS));
            Future<?> queued = pool.submit(() -> {
            });
            waitUntil(() -> pool.getQueue().size() == 1, 3000);

            // Schedule a periodic task while pool is saturated: must not kill periodic task
            ScheduledFuture<?> periodic = timer.scheduleAtFixedRate(periodicRan::countDown, 10, 40, TimeUnit.MILLISECONDS);
            // Also schedule a one-shot task that will be rejected fast without hanging the timer thread
            ScheduledFuture<?> oneShot = timer.schedule(() -> {
            }, 15, TimeUnit.MILLISECONDS);

            ExecutionException err = assertThrows(ExecutionException.class, () -> oneShot.get(2, TimeUnit.SECONDS));
            assertTrue(err.getCause() instanceof RejectedExecutionException);
            assertTrue(timer.rejectedCount.sum() >= 1L);
            assertFalse(periodic.isDone(), "periodic task must survive transient pool saturation");

            // Release pool saturation -> periodic task must recover and execute
            blockWorker.countDown();
            running.get(3, TimeUnit.SECONDS);
            queued.get(3, TimeUnit.SECONDS);
            assertTrue(periodicRan.await(3, TimeUnit.SECONDS), "periodic task should resume after pool recovers");
            periodic.cancel(true);
        }
    }

    @Test
    void tp03NettyEventLoopSubmitMustBypassBlockAndCallerRuns() throws Exception {
        RxConfig.ThreadPoolConfig conf = RxConfig.INSTANCE.getThreadPool();
        NioEventLoopGroup group = new NioEventLoopGroup(1);
        CountDownLatch blockWorker = new CountDownLatch(1);
        CountDownLatch workerStarted = new CountDownLatch(1);
        AtomicBoolean ranOnEventLoop = new AtomicBoolean();
        try (ThreadPoolConfigSnapshot ignored = ThreadPoolConfigSnapshot.capture()) {
            ThreadPool pool = track(ThreadPool.fixed("TP03-EL", 1, 1));
            Future<?> running = pool.submit(() -> {
                workerStarted.countDown();
                try {
                    blockWorker.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(workerStarted.await(3, TimeUnit.SECONDS));
            Future<?> queued = pool.submit(() -> {
            });
            waitUntil(() -> pool.getQueue().size() == 1, 3000);

            EventLoop eventLoop = group.next();

            // 1. BLOCK mode: EventLoop must reject immediately without blocking
            conf.setQueueOfferMode(ThreadPoolQueueOfferMode.BLOCK);
            CompletableFuture<Throwable> blockRes = new CompletableFuture<>();
            eventLoop.execute(() -> {
                try {
                    pool.execute(() -> ranOnEventLoop.set(true));
                    blockRes.complete(null);
                } catch (Throwable t) {
                    blockRes.complete(t);
                }
            });
            assertTrue(blockRes.get(2, TimeUnit.SECONDS) instanceof RejectedExecutionException);

            // 2. CALLER_RUNS mode: EventLoop must also reject immediately, never inline business logic
            conf.setQueueOfferMode(ThreadPoolQueueOfferMode.CALLER_RUNS);
            conf.setQueueOfferTimeoutMillis(0);
            CompletableFuture<Throwable> callerRunsRes = new CompletableFuture<>();
            eventLoop.execute(() -> {
                try {
                    pool.execute(() -> ranOnEventLoop.set(true));
                    callerRunsRes.complete(null);
                } catch (Throwable t) {
                    callerRunsRes.complete(t);
                }
            });
            assertTrue(callerRunsRes.get(2, TimeUnit.SECONDS) instanceof RejectedExecutionException);
            assertFalse(ranOnEventLoop.get(), "EventLoop must never inline business task in CALLER_RUNS mode");
            assertTrue(((ThreadPool.ThreadQueue) pool.getQueue()).offerNonBlockingBypassCount.sum() >= 2L);

            // 3. Explicit tryExecute / tryRunAsync on regular thread
            assertFalse(pool.tryExecute(() -> {
            }));
            CompletableFuture<Void> failed = pool.tryRunAsync(() -> {
            });
            assertTrue(failed.isCompletedExceptionally());

            blockWorker.countDown();
            running.get(3, TimeUnit.SECONDS);
            queued.get(3, TimeUnit.SECONDS);
        } finally {
            blockWorker.countDown();
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }

    // ========== TP-05: Tasks hot replica scaling and safe shrink draining ==========

    @Test
    void tp05TasksReplicaHotScalingShouldPreserveAllTasksAndDrainRetiredPools() throws Exception {
        int originalReplicas = RxConfig.INSTANCE.getThreadPool().getReplicas();
        long originalGrace = Tasks.retiringGraceNanos;
        long originalSweep = Tasks.retiringSweepPeriodMillis;
        ExecutorService submitters = Executors.newFixedThreadPool(4);
        try {
            Tasks.retiringGraceNanos = TimeUnit.MILLISECONDS.toNanos(50);
            Tasks.retiringSweepPeriodMillis = 50L;
            RxConfig.INSTANCE.getThreadPool().setReplicas(4);
            Tasks.createPool(null);
            assertEquals(4, Tasks.activePools.length);

            AtomicInteger executed = new AtomicInteger();
            AtomicInteger submittedCount = new AtomicInteger();
            AtomicBoolean stop = new AtomicBoolean();
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < 4; t++) {
                futures.add(submitters.submit(() -> {
                    while (!stop.get()) {
                        Future<?> f = Tasks.run(executed::incrementAndGet);
                        submittedCount.incrementAndGet();
                        try {
                            f.get(5, TimeUnit.SECONDS);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }
                }));
            }

            RxConfig.INSTANCE.getThreadPool().setReplicas(5);
            Tasks.createPool(null);
            assertEquals(5, Tasks.activePools.length);
            ThreadPool[] beforeShrink = Tasks.activePools;
            RxConfig.INSTANCE.getThreadPool().setReplicas(2);
            Tasks.createPool(null);
            assertEquals(2, Tasks.activePools.length);

            stop.set(true);
            for (Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
            assertEquals(submittedCount.get(), executed.get());
            // Retired pools should eventually be drained and shut down
            waitUntil(() -> {
                Tasks.drainRetiringPoolsNow();
                return beforeShrink[2].isShutdown() && beforeShrink[3].isShutdown() && beforeShrink[4].isShutdown();
            }, 5000);
        } finally {
            submitters.shutdownNow();
            Tasks.retiringGraceNanos = originalGrace;
            Tasks.retiringSweepPeriodMillis = originalSweep;
            RxConfig.INSTANCE.getThreadPool().setReplicas(originalReplicas);
            Tasks.createPool(null);
        }
    }

    // ========== TP-06: ThreadQueue inherited APIs & purge() slot/counter invariants ==========

    @Test
    void tp06ThreadQueueInheritedApisAndPurgeKeepCounterAndSlotsConsistent() throws Exception {
        ThreadPool pool = track(ThreadPool.fixed("TP06-QUEUE", 1, 4));
        ThreadPool.ThreadQueue queue = (ThreadPool.ThreadQueue) pool.getQueue();
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);

        Future<?> active = pool.submit(() -> {
            workerStarted.countDown();
            try {
                releaseWorker.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(workerStarted.await(3, TimeUnit.SECONDS));

        Future<?> c1 = pool.submit(() -> {
        });
        Future<?> c2 = pool.submit(() -> {
        });
        assertEquals(2, queue.size());
        assertEquals(2, queue.availableSlots.availablePermits());

        // Cancel both and call pool.purge() which uses queue.iterator().remove()
        assertTrue(c1.cancel(false));
        assertTrue(c2.cancel(false));
        pool.purge();

        assertEquals(0, queue.size(), "purge() must decrement counter via iterator.remove()");
        assertEquals(4, queue.availableSlots.availablePermits(), "purge() must release slots via iterator.remove()");

        // Verify direct add / offer(timeout) / iterator.remove invariants
        Runnable r1 = () -> {
        };
        assertTrue(queue.add(r1));
        assertEquals(1, queue.size());
        assertEquals(3, queue.availableSlots.availablePermits());

        Iterator<Runnable> it = queue.iterator();
        assertTrue(it.hasNext());
        assertEquals(r1, it.next());
        it.remove();
        assertEquals(0, queue.size());
        assertEquals(4, queue.availableSlots.availablePermits());

        // Direct transfer / tryTransfer must be rejected explicitly
        assertThrows(UnsupportedOperationException.class, () -> queue.transfer(() -> {
        }));
        assertThrows(UnsupportedOperationException.class, () -> queue.tryTransfer(() -> {
        }));
        assertThrows(UnsupportedOperationException.class, () -> queue.tryTransfer(() -> {
        }, 10, TimeUnit.MILLISECONDS));

        releaseWorker.countDown();
        active.get(3, TimeUnit.SECONDS);
    }

    // ========== TP-07: SINGLE validation & beforeExecute failure rollback ==========

    @Test
    void tp07SingleFlagWithoutTaskIdShouldFailFastAtSubmission() {
        ThreadPool pool = track(ThreadPool.fixed("TP07-SINGLE", 1, 4));
        assertThrows(InvalidException.class, () -> pool.runAsync(() -> {
        }, null, RunFlag.SINGLE.flags()));
        assertThrows(InvalidException.class, () -> pool.run(() -> {
        }, null, RunFlag.SINGLE.flags()));
    }

    @Test
    void tp07BeforeExecuteExceptionShouldRollbackLocksAndCompleteFutures() throws Exception {
        String singleId = "tp07-rollback-" + UUID.randomUUID();
        String serialId = "tp07-serial-" + UUID.randomUUID();
        FastThreadLocal<String> local = new FastThreadLocal<>();
        AtomicBoolean failNext = new AtomicBoolean(true);
        ThreadPool pool = track(new ThreadPool(1, 8, null, "TP07-FAIL") {
            @Override
            void afterBeforeExecuteSetup(Thread t, Runnable r, Task<?> task) {
                if (failNext.compareAndSet(true, false)) {
                    throw new IllegalStateException("injected beforeExecute failure");
                }
            }
        });
        try {
            local.set("submit-val");

            // 1. AsyncTaskRunnable: beforeExecute acquires SINGLE + FTL + trace, then fails
            CompletableFuture<String> asyncFail = pool.runAsync(() -> "should-not-run",
                    singleId, RunFlag.SINGLE.flags(RunFlag.THREAD_TRACE, RunFlag.INHERIT_FAST_THREAD_LOCALS));
            ExecutionException asyncErr = assertThrows(ExecutionException.class, () -> asyncFail.get(3, TimeUnit.SECONDS));
            assertTrue(asyncErr.getCause() instanceof IllegalStateException);
            assertFalse(ThreadPool.runningSingleTasks.contains(singleId),
                    "SINGLE lock must be rolled back when beforeExecute fails");

            // 2. FutureTaskAdapter: also fails cleanly instead of hanging forever
            failNext.set(true);
            Future<String> futureFail = pool.run(() -> "should-not-run",
                    singleId, RunFlag.SINGLE.flags(RunFlag.THREAD_TRACE));
            ExecutionException futureErr = assertThrows(ExecutionException.class, () -> futureFail.get(3, TimeUnit.SECONDS));
            assertTrue(futureErr.getCause() instanceof IllegalStateException);
            assertFalse(ThreadPool.runningSingleTasks.contains(singleId));

            // 3. SerialRunnable: first fails inside beforeExecute, chain MUST advance to second
            failNext.set(true);
            CompletableFuture<String> s1 = pool.runSerialAsync(() -> "s1-never",
                    serialId, RunFlag.SERIAL.flags(RunFlag.SINGLE, RunFlag.THREAD_TRACE));
            CompletableFuture<String> s2 = pool.runSerialAsync(() -> "s2-ok", serialId);
            ExecutionException s1Err = assertThrows(ExecutionException.class, () -> s1.get(3, TimeUnit.SECONDS));
            assertTrue(s1Err.getCause() instanceof IllegalStateException);
            assertEquals("s2-ok", s2.get(3, TimeUnit.SECONDS));
            assertFalse(ThreadPool.runningSingleTasks.contains(serialId));
            waitUntil(() -> !ThreadPool.taskSerialMap.containsKey(serialId), 3000);
        } finally {
            local.remove();
        }
    }

    // ========== TP-08: CALLER_RUNS must not execute on interrupt or shutdown ==========

    @Test
    void tp08CallerRunsMustNotRunTaskWhenInterruptedOrShutdownDuringWait() throws Exception {
        RxConfig.ThreadPoolConfig conf = RxConfig.INSTANCE.getThreadPool();
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        AtomicBoolean taskExecuted = new AtomicBoolean();
        try (ThreadPoolConfigSnapshot ignored = ThreadPoolConfigSnapshot.capture()) {
            conf.setQueueOfferMode(ThreadPoolQueueOfferMode.CALLER_RUNS);
            conf.setQueueOfferTimeoutMillis(5000);
            ThreadPool pool = track(ThreadPool.fixed("TP08-INT", 1, 1));
            ThreadPool.ThreadQueue queue = (ThreadPool.ThreadQueue) pool.getQueue();

            Future<?> running = pool.submit(() -> {
                workerStarted.countDown();
                try {
                    releaseWorker.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(workerStarted.await(3, TimeUnit.SECONDS));
            Future<?> queued = pool.submit(() -> {
            });
            waitUntil(() -> queue.size() == 1, 3000);

            CountDownLatch callerWaiting = new CountDownLatch(1);
            AtomicBoolean interruptedFlagPreserved = new AtomicBoolean();
            AtomicReference<Throwable> callerException = new AtomicReference<>();
            Thread caller = new Thread(() -> {
                callerWaiting.countDown();
                try {
                    pool.execute(() -> taskExecuted.set(true));
                } catch (Throwable t) {
                    callerException.set(t);
                    interruptedFlagPreserved.set(Thread.currentThread().isInterrupted());
                }
            }, "tp08-caller");
            caller.start();
            assertTrue(callerWaiting.await(3, TimeUnit.SECONDS));
            Thread.sleep(80);
            caller.interrupt();
            caller.join(3000);

            assertFalse(caller.isAlive());
            assertFalse(taskExecuted.get(), "interrupted caller must NOT run task inline");
            assertTrue(callerException.get() instanceof RejectedExecutionException);
            assertTrue(interruptedFlagPreserved.get(), "interrupt status must be preserved");
            assertEquals(1L, queue.offerRejectedCount.sum(), "offerRejectedCount should increment exactly once");
            assertEquals(0L, queue.offerCallerRunsCount.sum());

            releaseWorker.countDown();
            running.get(3, TimeUnit.SECONDS);
            queued.get(3, TimeUnit.SECONDS);
            assertEquals(1, queue.availableSlots.availablePermits(), "no slot permit leaked");
        }
    }

    // ========== TP-10 / TP-11 / TP-12 / TP-13 ==========

    @Test
    void tp10CpuWatchmanConcurrentResizeStaysWithinBounds() throws Exception {
        ThreadPool pool = track(new ThreadPool(2, 16, null, "TP10-WATCH"));
        ExecutorService exec = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < 4; t++) {
                final int idx = t;
                futures.add(exec.submit(() -> {
                    for (int i = 0; i < 50; i++) {
                        if ((idx + i) % 2 == 0) {
                            CpuWatchman.incrSize(pool);
                        } else {
                            CpuWatchman.decrSize(pool);
                        }
                        int core = pool.getCorePoolSize();
                        assertTrue(core >= pool.minIdleSize() && core <= pool.maxPoolSize());
                    }
                }));
            }
            for (Future<?> f : futures) {
                f.get(5, TimeUnit.SECONDS);
            }
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    void tp11TasksP2cShouldAvoidBackloggedReplica() throws Exception {
        int originalReplicas = RxConfig.INSTANCE.getThreadPool().getReplicas();
        try {
            RxConfig.INSTANCE.getThreadPool().setReplicas(2);
            Tasks.createPool(null);
            ThreadPool[] snapshot = Tasks.activePools;
            assertEquals(2, snapshot.length);
            ThreadPool.ThreadQueue q0 = (ThreadPool.ThreadQueue) snapshot[0].getQueue();
            // Simulate backlog on replica 0
            q0.counter.addAndGet(50);
            try {
                int pickedReplica1 = 0;
                for (int i = 0; i < 20; i++) {
                    if (Tasks.nextPool(null, null, null) == snapshot[1]) {
                        pickedReplica1++;
                    }
                }
                assertEquals(20, pickedReplica1, "P2C with 2 replicas must consistently pick idle replica over backlogged one");
            } finally {
                q0.counter.addAndGet(-50);
            }
        } finally {
            RxConfig.INSTANCE.getThreadPool().setReplicas(originalReplicas);
            Tasks.createPool(null);
        }
    }

    @Test
    void tp12InheritFastThreadLocalsShouldSnapshotAtSubmitTime() throws Exception {
        ThreadPool pool = track(ThreadPool.fixed("TP12-FTL", 1, 8));
        FastThreadLocal<String> local = new FastThreadLocal<>();
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        AtomicReference<String> childObserved = new AtomicReference<>();
        try {
            Future<?> blocker = pool.submit(() -> {
                blockerStarted.countDown();
                try {
                    releaseBlocker.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(blockerStarted.await(3, TimeUnit.SECONDS));

            local.set("at-submit");
            CompletableFuture<Void> child = pool.runAsync(() -> childObserved.set(local.get()),
                    null, RunFlag.INHERIT_FAST_THREAD_LOCALS.flags());

            // Parent mutates FTL BEFORE child even starts executing
            local.set("mutated-later");
            releaseBlocker.countDown();

            blocker.get(3, TimeUnit.SECONDS);
            child.get(3, TimeUnit.SECONDS);
            assertEquals("at-submit", childObserved.get(),
                    "child must observe FTL snapshot from submit time, not later parent mutation");
        } finally {
            releaseBlocker.countDown();
            local.remove();
        }
    }

    @Test
    void tp13DefaultPatchCompletableFutureAsyncPoolIsFalse() {
        assertFalse(RxConfig.INSTANCE.getThreadPool().isPatchCompletableFutureAsyncPool());
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
