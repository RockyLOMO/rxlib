package org.rx.core;

import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;
import org.rx.exception.InvalidException;

import java.io.Closeable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ObjectPoolRecycleOwnershipTest {
    @Test
    public void testDoubleRecycleDoesNotPassivateTwice() throws Exception {
        AtomicInteger passivated = new AtomicInteger();
        ObjectPool<TestResource> pool = new ObjectPool<>(0, 1,
                () -> new TestResource(1),
                x -> !x.closed,
                null,
                x -> passivated.incrementAndGet());
        try {
            TestResource resource = pool.borrow();
            pool.recycle(resource);

            assertThrows(InvalidException.class, () -> pool.recycle(resource));
            assertEquals(1, passivated.get(), "double recycle must not execute passivate twice");
        } finally {
            pool.close();
        }
    }

    @SneakyThrows
    @Test
    public void testConcurrentRecycleOnlyOneThreadPassivates() {
        CountDownLatch passivateStarted = new CountDownLatch(1);
        CountDownLatch releasePassivate = new CountDownLatch(1);
        AtomicInteger passivated = new AtomicInteger();
        AtomicInteger invalidRecycle = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();
        ObjectPool<TestResource> pool = new ObjectPool<>(0, 1,
                () -> new TestResource(1),
                x -> !x.closed,
                null,
                x -> {
                    passivated.incrementAndGet();
                    passivateStarted.countDown();
                    releasePassivate.await(2, TimeUnit.SECONDS);
                });
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            TestResource resource = pool.borrow();
            Future<?> first = executor.submit(() -> pool.recycle(resource));
            assertTrue(passivateStarted.await(2, TimeUnit.SECONDS), "first recycle should enter passivate");

            Future<?> second = executor.submit(() -> {
                try {
                    pool.recycle(resource);
                } catch (InvalidException e) {
                    invalidRecycle.incrementAndGet();
                } catch (Throwable e) {
                    unexpected.incrementAndGet();
                }
            });

            releasePassivate.countDown();
            first.get(2, TimeUnit.SECONDS);
            second.get(2, TimeUnit.SECONDS);

            assertEquals(1, passivated.get(), "only the CAS owner should execute passivate");
            assertEquals(1, invalidRecycle.get(), "concurrent duplicate recycle should keep invalid recycle semantics");
            assertEquals(0, unexpected.get(), "no unexpected exception should be thrown");

            TestResource borrowedAgain = pool.borrow();
            assertNotNull(borrowedAgain);
            pool.recycle(borrowedAgain);
        } finally {
            releasePassivate.countDown();
            executor.shutdownNow();
            pool.close();
        }
    }

    @SneakyThrows
    @Test
    public void testStaleThreadLocalHintDoesNotBorrowRetiredObject() {
        AtomicInteger created = new AtomicInteger();
        ObjectPool<TestResource> pool = new ObjectPool<>(0, 2,
                () -> new TestResource(created.incrementAndGet()),
                x -> !x.closed);
        try {
            TestResource first = pool.borrow();
            pool.recycle(first);
            assertEquals(1, pool.idleSize());

            first.closed = true;
            waitForCondition(() -> {
                pool.validNow();
                return !pool.anyMatch(x -> x == first);
            }, 3000, "invalid idle object should be retired");

            TestResource second = pool.borrow();
            assertNotNull(second);
            assertFalse(second.closed);
            assertTrue(second.id != first.id, "borrow should not return stale retired ThreadLocal object");
            pool.recycle(second);
        } finally {
            pool.close();
        }
    }

    private static void waitForCondition(Condition condition, long timeoutMs, String message) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.ok()) {
                return;
            }
            Thread.sleep(10);
        }
        assertTrue(condition.ok(), message);
    }

    private interface Condition {
        boolean ok() throws Exception;
    }

    static final class TestResource implements Closeable {
        final int id;
        volatile boolean closed;

        TestResource(int id) {
            this.id = id;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
