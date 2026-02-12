package org.rx.core;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.rx.exception.InvalidException;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Slf4j
public class ObjectPoolTest {
    @Test
    public void testBasicOps() throws TimeoutException {
        ObjectPool<Object> pool = new ObjectPool<>(2, 4,
                Object::new,
                x -> true);

        Object i1 = pool.borrow();
        Object i2 = pool.borrow();
        System.out.println("Borrowed: " + i1 + ", " + i2);

        pool.recycle(i1);
        pool.recycle(i2);

        assertTrue(pool.size() >= 2 && pool.size() <= 4, "Size should be between 2 and 4, actual: " + pool.size());
    }

    @Test
    public void testMaxSize() throws TimeoutException {
        int maxSize = 3;
        AtomicInteger created = new AtomicInteger();
        ObjectPool<Object> pool = new ObjectPool<>(0, maxSize,
                () -> {
                    created.incrementAndGet();
                    return new Object();
                },
                x -> true);

        // Borrow maxSize objects
        for (int i = 0; i < maxSize; i++) {
            pool.borrow();
        }
        assertEquals(maxSize, created.get());

        // Try to borrow one more, should timeout
        pool.setBorrowTimeout(500);
        assertThrows(TimeoutException.class, pool::borrow);

        assertEquals(maxSize, created.get());
    }

    @Test
    public void testBorrowTimeout() {
        ObjectPool<Object> pool = new ObjectPool<>(0, 1,
                Object::new,
                x -> true);

        try {
            pool.borrow(); // Take the only one
        } catch (TimeoutException e) {
            fail(e);
        }

        pool.setBorrowTimeout(1000);
        long start = System.currentTimeMillis();
        assertThrows(TimeoutException.class, pool::borrow);
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed >= 900, "Should wait for approx 1000ms, actual: " + elapsed);
        assertTrue(elapsed < 2000, "Should not wait too long");
    }

    @SneakyThrows
    @Test
    public void testConcurrency() {
        int threads = 10;
        int loops = 50;
        int maxSize = 5;
        AtomicInteger created = new AtomicInteger();
        ObjectPool<Object> pool = new ObjectPool<>(2, maxSize,
                () -> {
                    created.incrementAndGet();
                    return new Object();
                },
                x -> true);

        CountDownLatch latch = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    for (int j = 0; j < loops; j++) {
                        Object val = pool.borrow();
                        assertNotNull(val);
                        Thread.sleep(5);
                        pool.recycle(val);
                    }
                } catch (Exception e) {
                    log.error("Error", e);
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        latch.await(30, TimeUnit.SECONDS);
        assertTrue(created.get() <= maxSize + threads, "Created too many objects: " + created.get());
    }
}
