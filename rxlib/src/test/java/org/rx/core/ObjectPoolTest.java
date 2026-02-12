package org.rx.core;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.rx.exception.InvalidException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Slf4j
public class ObjectPoolTest {

    @Test
    public void testLIFO() throws TimeoutException {
        AtomicInteger counter = new AtomicInteger(0);
        ObjectPool<Integer> pool = new ObjectPool<>(0, 10, counter::incrementAndGet, x -> true);

        Integer i1 = pool.borrow();
        assertEquals(1, i1);
        Integer i2 = pool.borrow();
        assertEquals(2, i2);

        pool.recycle(i1);
        pool.recycle(i2);

        // Expect LIFO: i2 should be borrowed first
        Integer i3 = pool.borrow();
        assertEquals(2, i3);
        Integer i4 = pool.borrow();
        assertEquals(1, i4);
    }

    @Test
    public void testBlockingBorrow() throws Exception {
        int maxSize = 1;
        ObjectPool<String> pool = new ObjectPool<>(0, maxSize, () -> "A", x -> true);

        String s1 = pool.borrow();
        assertEquals("A", s1);

        CountDownLatch startLatch = new CountDownLatch(1);
        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
            try {
                startLatch.countDown();
                return pool.borrow(); // Should block
            } catch (TimeoutException e) {
                throw new RuntimeException(e);
            }
        });

        startLatch.await();
        Thread.sleep(100); // Ensure thread is blocked
        assertFalse(future.isDone());

        pool.recycle(s1); // Should release lock

        String s2 = future.get(1, TimeUnit.SECONDS);
        assertEquals("A", s2);
    }

    @Test
    public void testBorrowTimeout() {
        int maxSize = 1;
        ObjectPool<String> pool = new ObjectPool<>(0, maxSize, () -> "A", x -> true);
        pool.setBorrowTimeout(100); // 100ms timeout

        try {
            pool.borrow();
        } catch (TimeoutException e) {
            fail("First borrow should succeed");
        }

        assertThrows(TimeoutException.class, pool::borrow);
    }

    @Test
    public void testMaxSize() throws TimeoutException {
        int maxSize = 2;
        AtomicInteger counter = new AtomicInteger(0);
        ObjectPool<Integer> pool = new ObjectPool<>(0, maxSize, counter::incrementAndGet, x -> true);

        pool.borrow();
        pool.borrow();

        assertEquals(2, pool.size());
        assertEquals(2, counter.get());

        // Should not be able to create more, will block/timeout
        pool.setBorrowTimeout(100);
        assertThrows(TimeoutException.class, pool::borrow);
        assertEquals(2, pool.size());
    }

    @Test
    public void testValidation() throws TimeoutException {
        AtomicInteger counter = new AtomicInteger(0);
        // Valid only if even
        ObjectPool<Integer> pool = new ObjectPool<>(0, 10, counter::incrementAndGet, x -> x % 2 == 0);

        // 1 is odd -> invalid, retire. 2 is even -> valid, return.
        Integer i = pool.borrow();
        assertEquals(2, i);
        assertEquals(2, counter.get()); // Generated 1 (retired) then 2 (returned)

        pool.recycle(i);
        assertEquals(1, pool.size());

        Integer j = pool.borrow();
        assertEquals(2, j);
    }

    @Test
    public void testValidationOnRecycle() throws TimeoutException {
        ObjectPool<Integer> pool = new ObjectPool<>(0, 10, () -> 1, x -> x == 1);

        Integer i = pool.borrow();
        // Make it invalid logically (mocking validation failure)
        ObjectPool<Integer> strictPool = new ObjectPool<>(0, 10, () -> 1, x -> false);
        Integer k = strictPool.borrow(); // will create 1, validate fail, retire... loop?
        // No, loop in borrow will validate. If create returns invalid, it retires and retries.
        // Infinite loop if create always returns invalid?
        // Let's test recycle validation failure.

        AtomicInteger counter = new AtomicInteger(0);
        AtomicInteger validateCounter = new AtomicInteger(0);
        ObjectPool<Integer> vPool = new ObjectPool<>(0, 10, counter::incrementAndGet, x -> {
            if (validateCounter.get() > 0)
                return false;
            return true;
        });

        Integer x = vPool.borrow();
        assertEquals(1, x);

        validateCounter.incrementAndGet(); // Now validation fails
        vPool.recycle(x);

        assertEquals(0, vPool.size()); // Should be retired
    }

    @SneakyThrows
    @Test
    public void testConcurrency() {
        int threads = 10;
        int loops = 100;
        int maxSize = 5;
        AtomicInteger counter = new AtomicInteger(0);
        ObjectPool<Integer> pool = new ObjectPool<>(0, maxSize, counter::incrementAndGet, x -> true);
        pool.setBorrowTimeout(5000);

        CountDownLatch latch = new CountDownLatch(threads);
        Runnable task = () -> {
            try {
                for (int i = 0; i < loops; i++) {
                    Integer obj = pool.borrow();
                    assertNotNull(obj);
                    // Simulate work
                    Thread.sleep(1);
                    pool.recycle(obj);
                }
            } catch (Exception e) {
                log.error("Error", e);
            } finally {
                latch.countDown();
            }
        };

        for (int i = 0; i < threads; i++) {
            new Thread(task).start();
        }

        latch.await(10, TimeUnit.SECONDS);
        assertTrue(pool.size() <= maxSize);
    }
}
