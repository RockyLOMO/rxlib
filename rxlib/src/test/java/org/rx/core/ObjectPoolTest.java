package org.rx.core;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.rx.exception.InvalidException;

import java.io.Closeable;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Slf4j
public class ObjectPoolTest {

    // ========== Basic Operations ==========

    @Test
    public void testBasicBorrowAndRecycle() throws TimeoutException {
        ObjectPool<Object> pool = new ObjectPool<>(2, 4,
                Object::new,
                x -> true);

        Object i1 = pool.borrow();
        Object i2 = pool.borrow();
        assertNotNull(i1);
        assertNotNull(i2);
        assertNotSame(i1, i2, "两次 borrow 应返回不同对象");

        pool.recycle(i1);
        pool.recycle(i2);

        assertTrue(pool.size() >= 2 && pool.size() <= 4,
                "Pool size should be between minSize and maxSize, actual: " + pool.size());
    }

    @Test
    public void testRecycleThenReBorrow() throws TimeoutException {
        ObjectPool<Object> pool = new ObjectPool<>(0, 2,
                Object::new,
                x -> true);

        Object obj1 = pool.borrow();
        pool.recycle(obj1);

        // 归还后再借，应该能拿到同一个对象（从 stack 中复用）
        Object obj2 = pool.borrow();
        assertSame(obj1, obj2, "归还后再借应复用同一对象");
    }

    // ========== MaxSize Enforcement ==========

    @Test
    public void testMaxSizeEnforcement() throws TimeoutException {
        int maxSize = 3;
        AtomicInteger created = new AtomicInteger();
        ObjectPool<Object> pool = new ObjectPool<>(0, maxSize,
                () -> {
                    created.incrementAndGet();
                    return new Object();
                },
                x -> true);

        // Borrow maxSize objects
        Object[] borrowed = new Object[maxSize];
        for (int i = 0; i < maxSize; i++) {
            borrowed[i] = pool.borrow();
        }
        assertEquals(maxSize, created.get(), "应恰好创建 maxSize 个对象");

        // borrow 超出上限应超时
        pool.setBorrowTimeout(500);
        assertThrows(TimeoutException.class, pool::borrow, "超出 maxSize 应抛 TimeoutException");

        // 创建数不应增加
        assertEquals(maxSize, created.get(), "超时后不应创建额外对象");
    }

    @Test
    public void testMaxSizeReleaseThenBorrow() throws TimeoutException {
        int maxSize = 2;
        ObjectPool<Object> pool = new ObjectPool<>(0, maxSize,
                Object::new,
                x -> true);

        Object o1 = pool.borrow();
        Object o2 = pool.borrow();
        assertEquals(maxSize, pool.size());

        // Pool 满，归还一个后应能再借
        pool.recycle(o1);
        Object o3 = pool.borrow();
        assertNotNull(o3, "归还后应能再次借出");
        assertEquals(maxSize, pool.size(), "总数应保持 maxSize");
    }

    // ========== Borrow Timeout ==========

    @Test
    public void testBorrowTimeoutAccuracy() {
        ObjectPool<Object> pool = new ObjectPool<>(0, 1,
                Object::new,
                x -> true);

        try {
            pool.borrow(); // 取走唯一名额
        } catch (TimeoutException e) {
            fail(e);
        }

        pool.setBorrowTimeout(1000);
        long start = System.currentTimeMillis();
        assertThrows(TimeoutException.class, pool::borrow);
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed >= 900, "Should wait ~1000ms, actual: " + elapsed);
        assertTrue(elapsed < 3000, "Should not wait too long, actual: " + elapsed);
    }

    @SneakyThrows
    @Test
    public void testBorrowUnblockedByRecycle() {
        ObjectPool<Object> pool = new ObjectPool<>(0, 1,
                Object::new,
                x -> true);
        pool.setBorrowTimeout(5000);

        Object obj = pool.borrow();

        // 在另一个线程中延迟归还
        new Thread(() -> {
            try {
                Thread.sleep(500);
                pool.recycle(obj);
            } catch (Exception e) {
                log.error("recycle error", e);
            }
        }).start();

        // 主线程 borrow 应被阻塞，直到另一线程归还
        long start = System.currentTimeMillis();
        Object borrowed = pool.borrow();
        long elapsed = System.currentTimeMillis() - start;

        assertNotNull(borrowed);
        // ~500ms 后 recycle，borrow 应该解阻塞
        assertTrue(elapsed >= 400 && elapsed < 3000,
                "borrow 应在 recycle 后解阻塞，actual elapsed: " + elapsed);
    }

    // ========== CreateHandler Failure ==========

    @Test
    public void testCreateHandlerThrowsThenRecovers() throws TimeoutException {
        AtomicInteger attempt = new AtomicInteger();
        ObjectPool<Object> pool = new ObjectPool<>(0, 2,
                () -> {
                    int a = attempt.incrementAndGet();
                    if (a == 1) {
                        throw new RuntimeException("creation failed");
                    }
                    return new Object();
                },
                x -> true);

        // doCreate 第一次失败后 totalCount 回退，第二次循环仍可创建
        // borrow 内部循环会重试：poll->create(fail)->poll(wait) 然后下一循环 poll->create(ok)
        pool.setBorrowTimeout(2000);
        Object obj = pool.borrow();
        assertNotNull(obj, "第二次创建应成功");
        assertEquals(1, pool.size(), "成功后 pool size 应为 1");
        assertTrue(attempt.get() >= 2, "至少尝试了 2 次创建");
    }

    // ========== Validation ==========

    @Test
    public void testValidationOnBorrow() throws TimeoutException {
        AtomicInteger created = new AtomicInteger();
        AtomicBoolean valid = new AtomicBoolean(true);
        ObjectPool<Object> pool = new ObjectPool<>(0, 3,
                () -> {
                    int c = created.incrementAndGet();
                    if (c >= 2) {
                        // 新对象创建后，恢复校验为通过
                        valid.set(true);
                    }
                    return new Object();
                },
                x -> valid.get());

        Object obj = pool.borrow(); // creates #1
        pool.recycle(obj); // 归还 → 对象回到 stack

        // 使 validate 返回 false
        valid.set(false);

        // borrow: poll → validate fails → retire → doCreate(#2, sets valid=true) → validate ok → return
        pool.setBorrowTimeout(2000);
        Object obj2 = pool.borrow();
        assertNotNull(obj2);
        assertNotSame(obj, obj2, "应返回新创建的对象");
        assertTrue(created.get() >= 2, "校验失败后应创建新对象");
    }

    @Test
    public void testInvalidObjectRetiredOnRecycle() throws TimeoutException {
        AtomicBoolean valid = new AtomicBoolean(true);
        ObjectPool<Object> pool = new ObjectPool<>(0, 2,
                Object::new,
                x -> valid.get());

        Object obj = pool.borrow();
        assertEquals(1, pool.size());

        // 归还时令 validate 失败
        valid.set(false);
        pool.recycle(obj);

        // 校验失败的对象应被 retire，不再占用 pool 槽位
        assertEquals(0, pool.size(), "无效对象归还后，pool size 应为 0");
    }

    // ========== Double Recycle ==========

    @Test
    public void testDoubleRecycleThrows() throws TimeoutException {
        ObjectPool<Object> pool = new ObjectPool<>(0, 2,
                Object::new,
                x -> true);

        Object obj = pool.borrow();
        pool.recycle(obj);

        // 重复归还应抛出 InvalidException
        assertThrows(InvalidException.class, () -> pool.recycle(obj),
                "重复归还同一对象应抛出异常");
    }

    // ========== Activate / Passivate Handlers ==========

    @SneakyThrows
    @Test
    public void testActivateAndPassivateHandlers() {
        AtomicInteger activated = new AtomicInteger();
        AtomicInteger passivated = new AtomicInteger();

        ObjectPool<Object> pool = new ObjectPool<>(0, 2,
                Object::new,
                x -> true,
                x -> activated.incrementAndGet(), // activateHandler
                x -> passivated.incrementAndGet() // passivateHandler
        );

        Object obj = pool.borrow();
        assertEquals(1, activated.get(), "borrow 应触发 activate");
        assertEquals(0, passivated.get());

        pool.recycle(obj);
        assertEquals(1, passivated.get(), "recycle 应触发 passivate");

        // 再次 borrow（从 stack 取出），应再次 activate
        Object obj2 = pool.borrow();
        assertEquals(2, activated.get(), "从 stack poll 也应触发 activate");
    }

    // ========== Dispose ==========

    @SneakyThrows
    @Test
    public void testDispose() {
        AtomicInteger closed = new AtomicInteger();
        ObjectPool<Closeable> pool = new ObjectPool<>(0, 3,
                () -> (Closeable) () -> closed.incrementAndGet(),
                x -> true);

        Closeable c1 = pool.borrow();
        Closeable c2 = pool.borrow();
        pool.recycle(c1);

        // dispose 应关闭所有对象（borrowed + idle）
        pool.close();

        assertTrue(closed.get() >= 1, "dispose 应关闭池中对象，关闭数: " + closed.get());
    }

    // ========== Concurrency ==========

    @SneakyThrows
    @Test
    public void testConcurrency() {
        int threads = 10;
        int loops = 50;
        int maxSize = 5;
        AtomicInteger created = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
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
                        Thread.sleep(2);
                        pool.recycle(val);
                    }
                } catch (Exception e) {
                    log.error("Concurrency error", e);
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS), "并发测试未在 30 秒内完成");
        assertEquals(0, errors.get(), "并发测试不应有错误");
        assertTrue(pool.size() <= maxSize, "pool size 不应超过 maxSize，actual: " + pool.size());
        log.info("Concurrency test: created={}, poolSize={}", created.get(), pool.size());
    }

    @SneakyThrows
    @Test
    public void testConcurrencyNoObjectLeak() {
        int maxSize = 3;
        int threads = 8;
        int loops = 30;
        AtomicInteger created = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();

        ObjectPool<Object> pool = new ObjectPool<>(0, maxSize,
                () -> {
                    created.incrementAndGet();
                    return new Object();
                },
                x -> true);
        pool.setBorrowTimeout(5000);

        CyclicBarrier barrier = new CyclicBarrier(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    barrier.await(); // 所有线程同时启动
                    for (int j = 0; j < loops; j++) {
                        Object val = pool.borrow();
                        Thread.sleep(1);
                        pool.recycle(val);
                    }
                } catch (Exception e) {
                    log.error("Leak test error", e);
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS), "泄漏测试未在 30 秒内完成");
        assertEquals(0, errors.get(), "泄漏测试不应有错误");

        // 所有对象归还后，pool size 不应超过 maxSize
        assertTrue(pool.size() <= maxSize,
                "totalCount 不应超过 maxSize，actual: " + pool.size() + ", created: " + created.get());
    }
}
