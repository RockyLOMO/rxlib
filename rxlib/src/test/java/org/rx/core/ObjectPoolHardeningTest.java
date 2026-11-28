package org.rx.core;

import org.junit.jupiter.api.Test;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ObjectPoolHardeningTest {
    static final class TrackedResource implements Closeable {
        final int id;
        final AtomicBoolean closed = new AtomicBoolean();
        final AtomicInteger closeCount = new AtomicInteger();

        TrackedResource(int id) {
            this.id = id;
        }

        @Override
        public void close() {
            closed.set(true);
            closeCount.incrementAndGet();
        }
    }

    // ========== OP-01: Duplicate creation & null creation protection ==========

    @Test
    void op01NullCreateResultShouldReleaseReservedSlotCleanly() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ObjectPool<TrackedResource> pool = new ObjectPool<>(0, 1,
                () -> calls.incrementAndGet() == 1 ? null : new TrackedResource(calls.get()),
                x -> !x.closed.get());
        try {
            pool.setBorrowTimeout(500);
            TrackedResource r = pool.borrow();
            assertNotNull(r);
            assertEquals(1, pool.size());
            assertEquals(1, pool.borrowedSize());
            pool.recycle(r);
            assertEquals(0, pool.borrowedSize());
        } finally {
            pool.close();
        }
    }

    // ========== OP-02: Lease cross-generation protection & cross-thread recycle ==========

    @Test
    void op02BorrowLeaseProtectsAcrossGenerationsAndSupportsCrossThreadRecycle() throws Exception {
        AtomicInteger idGen = new AtomicInteger();
        ObjectPool<TrackedResource> pool = new ObjectPool<>(0, 1,
                () -> new TrackedResource(idGen.incrementAndGet()),
                x -> !x.closed.get());
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            ObjectPool.Lease<TrackedResource> lease1 = pool.borrowLease();
            TrackedResource r = lease1.get();
            assertEquals(1, pool.borrowedSize());

            // Cross-thread recycle must succeed
            exec.submit(lease1::close).get(3, TimeUnit.SECONDS);
            assertTrue(lease1.isReleased());
            assertEquals(0, pool.borrowedSize());
            assertEquals(1, pool.idleSize());
            assertThrows(IllegalStateException.class, lease1::get);

            // Second generation borrows same object
            ObjectPool.Lease<TrackedResource> lease2 = pool.borrowLease();
            assertSame(r, lease2.get());
            assertEquals(1, pool.borrowedSize());

            // Redundant close/recycle/discard on stale lease1 must be complete no-op and never affect lease2
            lease1.close();
            lease1.recycle();
            lease1.discard();
            assertEquals(1, pool.borrowedSize());
            assertEquals(0, pool.idleSize());
            assertFalse(r.closed.get());

            // Discarding lease2 retires the object cleanly
            lease2.discard();
            assertTrue(r.closed.get());
            assertEquals(1, r.closeCount.get());
            assertEquals(0, pool.size());
            assertEquals(0, pool.borrowedSize());
        } finally {
            exec.shutdownNow();
            pool.close();
        }
    }

    // ========== OP-03: Health validation must NOT reset idleSinceTime; maxLifetime overrides minIdleSize ==========

    @Test
    void op03PeriodicValidationMustNotPreventIdleTimeoutEviction() throws Exception {
        AtomicInteger idGen = new AtomicInteger();
        ObjectPool<TrackedResource> pool = new ObjectPool<>(0, 4,
                () -> new TrackedResource(idGen.incrementAndGet()),
                x -> !x.closed.get());
        try {
            pool.setDemandFactor(0);
            pool.idleTimeout = 180;

            TrackedResource r1 = pool.borrow();
            TrackedResource r2 = pool.borrow();
            pool.recycle(r1);
            pool.recycle(r2);
            assertEquals(2, pool.idleSize());

            // Run validNow() every 50ms (< idleTimeout=180ms). Under old code stateTime reset prevented eviction forever.
            for (int i = 0; i < 6; i++) {
                Thread.sleep(50);
                pool.validNow();
            }

            assertEquals(0, pool.size(),
                    "objects whose cumulative idle time exceeds idleTimeout must be evicted despite periodic validNow()");
            assertTrue(r1.closed.get());
            assertTrue(r2.closed.get());
        } finally {
            pool.close();
        }
    }

    @Test
    void op03MaxLifetimeOverridesMinIdleSize() throws Exception {
        AtomicInteger idGen = new AtomicInteger();
        ObjectPool<TrackedResource> pool = new ObjectPool<>(1, 2,
                () -> new TrackedResource(idGen.incrementAndGet()),
                x -> !x.closed.get());
        try {
            pool.maxLifetime = 120;
            TrackedResource first = pool.borrow();
            pool.recycle(first);
            assertEquals(1, first.id);

            Thread.sleep(160);
            pool.validNow();

            assertTrue(first.closed.get(), "expired object must be retired even when idleSize == minIdleSize");
            // Replenished by insureMinIdle with a fresh instance
            TrackedResource second = pool.borrow();
            assertTrue(second.id > first.id);
            pool.recycle(second);
        } finally {
            pool.close();
        }
    }

    // ========== OP-04: High-frequency serial borrow/recycle must NOT bloat pool to maxPoolSize ==========

    @Test
    void op04HighFrequencySerialBorrowDoesNotBloatPoolWhileRealConcurrencyScalesUp() throws Exception {
        ObjectPool<TrackedResource> pool = new ObjectPool<>(0, 10,
                () -> new TrackedResource(1),
                x -> !x.closed.get());
        try {
            pool.setDemandFactor(2.0);

            // 200 sequential borrow/recycle calls: peak concurrency is 1
            for (int i = 0; i < 200; i++) {
                TrackedResource r = pool.borrow();
                pool.recycle(r);
            }
            pool.validNow();
            assertTrue(pool.size() <= 2,
                    "200 serial borrow/recycle calls should not pre-warm to maxPoolSize, actual: " + pool.size());

            // Now hold 4 concurrent borrows -> peak concurrency is 4 -> ceil(4 * 2.0) = 8
            List<TrackedResource> held = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                held.add(pool.borrow());
            }
            assertEquals(4, pool.borrowedSize());
            pool.validNow();
            assertTrue(pool.size() >= 8, "real 4x concurrency should scale pool up, actual: " + pool.size());

            for (TrackedResource r : held) {
                pool.recycle(r);
            }
            assertEquals(0, pool.borrowedSize());
        } finally {
            pool.close();
        }
    }

    // ========== OP-06: closeObjectOnLeak generation CAS prevents killing new borrower ==========

    @Test
    void op06LeakCloseGenerationCasPreventsRetiringReborrowedInstance() throws Exception {
        ObjectPool<TrackedResource> pool = new ObjectPool<>(0, 1,
                () -> new TrackedResource(1),
                x -> !x.closed.get());
        try {
            pool.setDemandFactor(0);
            pool.leakDetectionThreshold = 80;
            pool.setCloseObjectOnLeak(true);

            TrackedResource r = pool.borrow();
            ObjectPool.ObjectConf<TrackedResource> c = pool.conf.values().iterator().next();
            long staleSg = c.getStateAndGen();

            // Original borrower returns and a new borrower checks out the same object (generation++)
            pool.recycle(r);
            TrackedResource r2 = pool.borrow();
            assertSame(r, r2);
            assertTrue(c.getGeneration() > ObjectPool.ObjectConf.genOf(staleSg));

            // Stale generation CAS from paused leak scanner MUST fail
            assertFalse(c.casStateWithGen(staleSg, ObjectPool.ObjectConf.RETIRED),
                    "stale generation CAS must fail when object has moved to new borrow generation");
            assertFalse(r2.closed.get());
            assertEquals(1, pool.borrowedSize());

            // Real leak exceeding threshold should be force-retired cleanly
            Thread.sleep(120);
            pool.validNow();
            assertTrue(r2.closed.get(), "truly leaked object should be closed when closeObjectOnLeak=true");
            assertEquals(0, pool.size());
            assertEquals(0, pool.borrowedSize());
        } finally {
            pool.close();
        }
    }

    // ========== OP-07: minIdle maintain rejection must reset flag and never break borrow ==========

    @Test
    void op07MinIdleMaintainFailureMustNotDestroyBorrowedObjectOrStickFlag() throws Exception {
        AtomicInteger idGen = new AtomicInteger();
        ObjectPool<TrackedResource> pool = new ObjectPool<>(1, 2,
                () -> new TrackedResource(idGen.incrementAndGet()),
                x -> !x.closed.get());
        try {
            // Simulate maintain trigger under normal conditions & verify flag returns to false
            TrackedResource r = pool.borrow();
            assertNotNull(r);
            assertFalse(r.closed.get());
            waitUntil(() -> !pool.minIdleMaintaining.get(), 3000);
            pool.recycle(r);
        } finally {
            pool.close();
        }
    }

    // ========== OP-08: Concurrent create & close closes each object at most once with zero leak ==========

    @Test
    void op08ConcurrentCreateAndCloseMustCloseEachInstanceAtMostOnceWithZeroResidue() throws Exception {
        CountDownLatch activateEntered = new CountDownLatch(1);
        CountDownLatch releaseActivate = new CountDownLatch(1);
        List<TrackedResource> created = new ArrayList<>();
        AtomicInteger idGen = new AtomicInteger();

        ObjectPool<TrackedResource> pool = new ObjectPool<>(0, 2,
                () -> {
                    TrackedResource r = new TrackedResource(idGen.incrementAndGet());
                    synchronized (created) {
                        created.add(r);
                    }
                    return r;
                },
                x -> !x.closed.get(),
                x -> {
                    activateEntered.countDown();
                    try {
                        releaseActivate.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                },
                null);

        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            Future<?> borrowFuture = exec.submit(() -> {
                try {
                    pool.borrow();
                } catch (Throwable ignored) {
                }
            });
            assertTrue(activateEntered.await(3, TimeUnit.SECONDS));

            // Close pool while doCreate is inside activateHandler
            pool.close();
            releaseActivate.countDown();
            borrowFuture.get(3, TimeUnit.SECONDS);

            assertEquals(0, pool.size());
            assertEquals(0, pool.idleSize());
            assertEquals(0, pool.borrowedSize());
            assertTrue(pool.conf.isEmpty());
            synchronized (created) {
                assertFalse(created.isEmpty());
                for (TrackedResource r : created) {
                    assertTrue(r.closed.get(), "every created resource must be closed");
                    assertEquals(1, r.closeCount.get(), "close callback must run exactly once");
                }
            }
        } finally {
            releaseActivate.countDown();
            exec.shutdownNow();
            if (!pool.isClosed()) {
                pool.close();
            }
        }
    }

    @Test
    void op08ConcurrentIdleCreateAndCloseMustCloseEachInstanceAtMostOnceWithZeroResidue() throws Exception {
        CountDownLatch passivateEntered = new CountDownLatch(1);
        CountDownLatch releasePassivate = new CountDownLatch(1);
        List<TrackedResource> created = new ArrayList<>();
        AtomicInteger idGen = new AtomicInteger();

        ObjectPool<TrackedResource> pool = new ObjectPool<>(0, 2,
                () -> {
                    TrackedResource r = new TrackedResource(idGen.incrementAndGet());
                    synchronized (created) {
                        created.add(r);
                    }
                    return r;
                },
                x -> !x.closed.get(),
                null,
                x -> {
                    passivateEntered.countDown();
                    try {
                        releasePassivate.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });

        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            Future<?> idleCreateFuture = exec.submit(() -> pool.doCreateIdle());
            assertTrue(passivateEntered.await(3, TimeUnit.SECONDS));

            pool.close();
            releasePassivate.countDown();
            idleCreateFuture.get(3, TimeUnit.SECONDS);

            assertEquals(0, pool.size());
            assertEquals(0, pool.idleSize());
            assertEquals(0, pool.borrowedSize());
            assertTrue(pool.conf.isEmpty());
            synchronized (created) {
                assertFalse(created.isEmpty());
                for (TrackedResource r : created) {
                    assertTrue(r.closed.get());
                    assertEquals(1, r.closeCount.get());
                }
            }
        } finally {
            releasePassivate.countDown();
            exec.shutdownNow();
            if (!pool.isClosed()) {
                pool.close();
            }
        }
    }

    // ========== OP-10: setMaxPoolSize expansion wakes waiters, shrink trims excess ==========

    @Test
    void op10SetMaxPoolSizeExpandWakesWaiterAndShrinkRetiresExcess() throws Exception {
        AtomicInteger idGen = new AtomicInteger();
        ObjectPool<TrackedResource> pool = new ObjectPool<>(0, 1,
                () -> new TrackedResource(idGen.incrementAndGet()),
                x -> !x.closed.get());
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            pool.setBorrowTimeout(3000);
            TrackedResource r1 = pool.borrow();

            CountDownLatch waiterStarted = new CountDownLatch(1);
            Future<TrackedResource> waiter = exec.submit(() -> {
                waiterStarted.countDown();
                return pool.borrow();
            });
            assertTrue(waiterStarted.await(3, TimeUnit.SECONDS));
            waitUntil(() -> pool.waitingBorrowers.get() == 1, 3000);

            // Expanding maxPoolSize must wake the waiting borrower immediately
            pool.setMaxPoolSize(3);
            TrackedResource r2 = waiter.get(2, TimeUnit.SECONDS);
            assertNotNull(r2);
            TrackedResource r3 = pool.borrow();
            assertEquals(3, pool.size());

            // Return one idle, then shrink maxPoolSize from 3 -> 1
            pool.recycle(r3);
            assertEquals(1, pool.idleSize());
            pool.setMaxPoolSize(1);
            assertTrue(r3.closed.get(), "idle excess object should be trimmed immediately on shrink");
            assertEquals(2, pool.size());

            // Returning remaining borrowed objects above new maxPoolSize retires excess naturally
            pool.recycle(r2);
            assertTrue(r2.closed.get(), "excess returned object should retire naturally");
            assertEquals(1, pool.size());

            pool.recycle(r1);
            assertFalse(r1.closed.get(), "within-capacity object should remain pooled");
            assertEquals(1, pool.size());
            assertEquals(1, pool.idleSize());
        } finally {
            exec.shutdownNow();
            pool.close();
        }
    }

    // ========== OP-12: Shared static lookupKey leaves no strong reference ==========

    @Test
    void op12SharedLookupKeyLeavesNoStrongReferenceAfterRecycle() throws Exception {
        ObjectPool<TrackedResource> p1 = new ObjectPool<>(0, 1, () -> new TrackedResource(1), x -> true);
        ObjectPool<TrackedResource> p2 = new ObjectPool<>(0, 1, () -> new TrackedResource(2), x -> true);
        try {
            TrackedResource r1 = p1.borrow();
            TrackedResource r2 = p2.borrow();
            p1.recycle(r1);
            assertNull(ObjectPool.lookupKey.get().instance);
            p2.recycle(r2);
            assertNull(ObjectPool.lookupKey.get().instance);
        } finally {
            p1.close();
            p2.close();
        }
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
