package org.rx.core;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.rx.exception.InvalidException;

import java.io.Closeable;
import java.util.Arrays;
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
                "Pool size should be between minIdleSize and maxPoolSize, actual: " + pool.size());
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

    @SneakyThrows
    @Test
    public void testMinIdleSizeMaintainsSharedIdleObjects() {
        AtomicInteger created = new AtomicInteger();
        ObjectPool<Object> pool = new ObjectPool<>(0, 4,
                () -> {
                    created.incrementAndGet();
                    return new Object();
                },
                x -> true);
        pool.setMinIdleSize(2);

        waitForCondition(() -> pool.idleSize() == 2 && pool.size() == 2, 3000,
                "应预热出 2 个 idle 对象");

        Object b1 = pool.borrow();
        Object b2 = pool.borrow();
        assertNotNull(b1);
        assertNotNull(b2);

        waitForCondition(() -> pool.idleSize() == 2 && pool.size() == 4, 3000,
                "借出中对象时应补足 minIdleSize 个待命对象");
        assertEquals(4, created.get(), "borrow 后应后台补齐 idle buffer");
    }

    @SneakyThrows
    @Test
    public void testMinIdleSizeDisablesThreadLocalIdleCache() {
        ObjectPool<Object> pool = new ObjectPool<>(0, 3, Object::new, x -> true);
        pool.setMinIdleSize(1);

        waitForCondition(() -> pool.idleSize() == 1, 3000, "应至少保留 1 个共享 idle 对象");

        Object obj = pool.borrow();
        pool.recycle(obj);

        waitForCondition(() -> pool.idleSize() >= 1, 3000,
                "recycle 后 idle 对象应回到共享 stack，而不是只留在 thread-local");
    }

    // ========== MaxPoolSize Enforcement ==========

    @Test
    public void testMaxPoolSizeEnforcement() throws TimeoutException {
        int maxPoolSize = 3;
        AtomicInteger created = new AtomicInteger();
        ObjectPool<Object> pool = new ObjectPool<>(0, maxPoolSize,
                () -> {
                    created.incrementAndGet();
                    return new Object();
                },
                x -> true);

        // Borrow maxPoolSize objects
        Object[] borrowed = new Object[maxPoolSize];
        for (int i = 0; i < maxPoolSize; i++) {
            borrowed[i] = pool.borrow();
        }
        assertEquals(maxPoolSize, created.get(), "应恰好创建 maxPoolSize 个对象");

        // borrow 超出上限应超时
        pool.setBorrowTimeout(500);
        assertThrows(TimeoutException.class, pool::borrow, "超出 maxPoolSize 应抛 TimeoutException");

        // 创建数不应增加
        assertEquals(maxPoolSize, created.get(), "超时后不应创建额外对象");
    }

    @Test
    public void testMaxPoolSizeReleaseThenBorrow() throws TimeoutException {
        int maxPoolSize = 2;
        ObjectPool<Object> pool = new ObjectPool<>(0, maxPoolSize,
                Object::new,
                x -> true);

        Object o1 = pool.borrow();
        Object o2 = pool.borrow();
        assertEquals(maxPoolSize, pool.size());

        // Pool 满，归还一个后应能再借
        pool.recycle(o1);
        Object o3 = pool.borrow();
        assertNotNull(o3, "归还后应能再次借出");
        assertEquals(maxPoolSize, pool.size(), "总数应保持 maxPoolSize");
    }

    @SneakyThrows
    @Test
    public void testThreadLocalCachedObjectVisibleToOtherThreadWhenPoolFull() {
        ObjectPool<Object> pool = new ObjectPool<>(0, 1, Object::new, x -> true);
        pool.setBorrowTimeout(1000);

        Object obj = pool.borrow();
        pool.recycle(obj);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Object> future = executor.submit(() -> {
                Object borrowed = pool.borrow();
                pool.recycle(borrowed);
                return borrowed;
            });

            assertSame(obj, future.get(2, TimeUnit.SECONDS),
                    "ThreadLocal hint 不应隐藏 idle 对象，其他线程也必须可借出");
        } finally {
            executor.shutdownNow();
            pool.close();
        }
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

    @SneakyThrows
    @Test
    public void testBlockedBorrowerWakesUpWhenPoolClosed() {
        ObjectPool<Object> pool = new ObjectPool<>(0, 1, Object::new, x -> true);
        pool.setBorrowTimeout(5000);

        Object borrowed = pool.borrow();
        assertNotNull(borrowed);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Object> future = executor.submit(() -> pool.borrow());
            waitForCondition(() -> pool.waitingBorrowers.get() > 0, 2000,
                    "borrower 应进入等待队列");

            long start = System.currentTimeMillis();
            pool.close();

            ExecutionException e = assertThrows(ExecutionException.class,
                    () -> future.get(2, TimeUnit.SECONDS));
            assertTrue(e.getCause() instanceof TimeoutException || e.getCause() instanceof InvalidException,
                    "close 后等待 borrower 应快速失败，actual: " + e.getCause());
            assertTrue(System.currentTimeMillis() - start < 2000,
                    "close 应唤醒等待 borrower，而不是等待完整 borrowTimeout");
        } finally {
            executor.shutdownNow();
        }
    }

    @SneakyThrows
    @Test
    public void testRetireSignalsWaitingBorrowers() {
        AtomicInteger created = new AtomicInteger();
        ObjectPool<PooledResource> pool = new ObjectPool<>(0, 1,
                () -> new PooledResource(created.incrementAndGet()),
                x -> x.valid);
        pool.setBorrowTimeout(5000);

        PooledResource first = pool.borrow();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<PooledResource> future = executor.submit(() -> pool.borrow());
            waitForCondition(() -> pool.waitingBorrowers.get() > 0, 2000,
                    "borrower 应在池满时等待");

            first.valid = false;
            pool.recycle(first);

            PooledResource second = future.get(2, TimeUnit.SECONDS);
            assertNotNull(second);
            assertNotSame(first, second, "retire 释放容量后应创建并借出新对象");
            assertEquals(2, created.get(), "应只额外创建一个替换对象");
            pool.recycle(second);
        } finally {
            executor.shutdownNow();
            pool.close();
        }
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

    @SneakyThrows
    @Test
    public void testCreateHandlerContinuousFailureBackoff() {
        AtomicInteger attempts = new AtomicInteger();
        ObjectPool<Object> pool = new ObjectPool<>(0, 1,
                () -> {
                    attempts.incrementAndGet();
                    throw new RuntimeException("always fail");
                },
                x -> true);
        pool.setBorrowTimeout(500);

        assertThrows(TimeoutException.class, pool::borrow);

        assertTrue(attempts.get() <= 20,
                "持续创建失败时应有退避，避免 borrowTimeout 内 tight loop，actual: " + attempts.get());
        pool.close();
    }

    @SneakyThrows
    @Test
    public void testDuplicateCreatedObjectIsClosedOrRejectedCleanly() {
        AtomicInteger attempts = new AtomicInteger();
        PooledResource shared = new PooledResource(1);
        ObjectPool<PooledResource> pool = new ObjectPool<>(0, 2,
                () -> {
                    attempts.incrementAndGet();
                    return shared;
                },
                x -> !x.closed.get());
        pool.setBorrowTimeout(500);

        PooledResource first = pool.borrow();
        assertSame(shared, first);

        assertThrows(TimeoutException.class, pool::borrow,
                "createHandler 返回重复对象时应拒绝借出");
        assertTrue(shared.closed.get(), "重复创建出的对象应被关闭");
        assertTrue(shared.closeCount.get() > 0, "重复对象分支应调用 close");
        assertTrue(attempts.get() <= 20,
                "重复对象失败应走退避，避免 tight loop，actual: " + attempts.get());

        pool.recycle(first);
        assertEquals(0, pool.size(), "被关闭的已借出重复对象归还后应 retire");
        pool.close();
    }

    @SneakyThrows
    @Test
    public void testDuplicateIdleCreatedObjectIsClosedAndRetiredByValidation() {
        AtomicInteger attempts = new AtomicInteger();
        PooledResource shared = new PooledResource(1);
        ObjectPool<PooledResource> pool = new ObjectPool<>(1, 2,
                () -> {
                    attempts.incrementAndGet();
                    return shared;
                },
                x -> !x.closed.get());

        assertEquals(1, pool.size());
        pool.insureTargetSize(2);

        assertTrue(shared.closed.get(), "doCreateIdle 重复对象分支应关闭重复对象");
        pool.validNow();
        assertEquals(0, pool.size(), "已被 duplicate 分支关闭的 idle 对象应在 validate 时 retire");
        assertTrue(attempts.get() <= 4, "idle duplicate 不应反复创建，actual: " + attempts.get());
        pool.close();
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
    public void testThreadLocalValidationExceptionReleasesSlotOnBorrow() throws TimeoutException {
        AtomicInteger created = new AtomicInteger();
        AtomicBoolean failFirstOnBorrow = new AtomicBoolean(false);
        ObjectPool<PooledResource> pool = new ObjectPool<>(0, 1,
                () -> new PooledResource(created.incrementAndGet()),
                x -> {
                    if (failFirstOnBorrow.get() && x.id == 1) {
                        throw new RuntimeException("validate failed");
                    }
                    return true;
                });
        pool.setBorrowTimeout(1000);

        PooledResource first = pool.borrow();
        pool.recycle(first);
        failFirstOnBorrow.set(true);

        PooledResource second = pool.borrow();
        assertEquals(2, second.id, "L1 validate 异常后应 retire 旧对象并释放容量创建新对象");
        assertTrue(first.closed.get(), "异常校验对象应被关闭");
        assertEquals(1, pool.size(), "异常路径不能泄漏 totalCount 槽位");
        pool.recycle(second);
        pool.close();
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

    @SneakyThrows
    @Test
    public void testValidationStatePreventsBorrowWhileValidating() {
        CountDownLatch validationStarted = new CountDownLatch(1);
        CountDownLatch releaseValidation = new CountDownLatch(1);
        AtomicBoolean blockValidation = new AtomicBoolean(false);

        ObjectPool<PooledResource> pool = new ObjectPool<>(0, 1,
                () -> new PooledResource(1),
                x -> {
                    if (blockValidation.get()) {
                        validationStarted.countDown();
                        releaseValidation.await(2, TimeUnit.SECONDS);
                    }
                    return x.valid;
                });
        pool.setBorrowTimeout(3000);

        PooledResource obj = pool.borrow();
        pool.recycle(obj);
        blockValidation.set(true);

        ExecutorService validateExecutor = Executors.newSingleThreadExecutor();
        ExecutorService borrowExecutor = Executors.newSingleThreadExecutor();
        try {
            Future<?> validateFuture = validateExecutor.submit(pool::validNow);
            assertTrue(validationStarted.await(2, TimeUnit.SECONDS),
                    "后台 validate 应开始处理 idle 对象");

            Future<PooledResource> borrowFuture = borrowExecutor.submit(() -> pool.borrow());
            Thread.sleep(200);
            assertFalse(borrowFuture.isDone(),
                    "validate 进行中对象不能被 borrow 并发借出");

            releaseValidation.countDown();
            PooledResource borrowed = borrowFuture.get(2, TimeUnit.SECONDS);
            assertSame(obj, borrowed);
            pool.recycle(borrowed);
            validateFuture.get(2, TimeUnit.SECONDS);
        } finally {
            releaseValidation.countDown();
            validateExecutor.shutdownNow();
            borrowExecutor.shutdownNow();
            pool.close();
        }
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

    @SneakyThrows
    @Test
    public void testBorrowAfterPoolCloseShouldFailAndNotCreate() {
        AtomicInteger created = new AtomicInteger();
        ObjectPool<Object> pool = new ObjectPool<>(0, 1,
                () -> {
                    created.incrementAndGet();
                    return new Object();
                },
                x -> true);

        pool.close();

        assertThrows(InvalidException.class, pool::borrow,
                "close 后 borrow 应立即失败");
        assertEquals(0, created.get(), "close 后不应再调用 createHandler");
    }

    // ========== Concurrency ==========

    @SneakyThrows
    @Test
    public void testConcurrency() {
        int threads = 10;
        int loops = 50;
        int maxPoolSize = 5;
        AtomicInteger created = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        ObjectPool<Object> pool = new ObjectPool<>(2, maxPoolSize,
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
        assertTrue(pool.size() <= maxPoolSize, "pool size 不应超过 maxPoolSize，actual: " + pool.size());
        log.info("Concurrency test: created={}, poolSize={}", created.get(), pool.size());
    }

    // ========== Adaptive Refill ==========

    /**
     * 基线：demandFactor=0 时无论 borrow 频率多高，targetSize 始终等于 minIdleSize。
     */
    @Test
    public void testAdaptiveRefillBaseline() {
        ObjectPool<Object> pool = new ObjectPool<>(2, 10, Object::new, x -> true);
        pool.setDemandFactor(0);

        // 直接填入极高采样值
        Arrays.fill(pool.borrowSamples, 100L);
        pool.validNow();

        assertEquals(2, pool.size(), "demandFactor=0 时 pool size 应等于 minIdleSize");
    }

    /**
     * 高负载：采样平均值高时，pool 应预热到 targetSize。
     * SAMPLE_COUNT=12，每格 10 次 → avgPerPeriod=10 → target=ceil(10*2.0)=20，上限 maxPoolSize=20。
     */
    @Test
    public void testAdaptiveRefillHighLoad() {
        ObjectPool<Object> pool = new ObjectPool<>(2, 20, Object::new, x -> true);
        pool.setDemandFactor(2.0);

        Arrays.fill(pool.borrowSamples, 10L);
        pool.validNow();

        assertTrue(pool.size() >= 10,
                "高负载时 pool 应被预热到 targetSize，actual: " + pool.size());
    }

    /**
     * 上限保护：target 超过 maxPoolSize 时，pool size 不得超过 maxPoolSize。
     */
    @Test
    public void testAdaptiveRefillMaxPoolSizeBound() {
        int maxPoolSize = 5;
        ObjectPool<Object> pool = new ObjectPool<>(2, maxPoolSize, Object::new, x -> true);
        pool.setDemandFactor(10.0);

        // 极高采样 → target 计算结果远超 maxPoolSize
        Arrays.fill(pool.borrowSamples, 100L);
        pool.validNow();

        assertEquals(maxPoolSize, pool.size(), "pool size 不应超过 maxPoolSize");
    }

    /**
     * 负载回落：样本清零后 targetSize 回落到 minIdleSize，idleTimeout 淘汰多余对象。
     * 直接写入 package-private 字段 idleTimeout 绕过 setter 的最小值限制。
     */
    @SneakyThrows
    @Test
    public void testAdaptiveRefillLoadDrop() {
        int minIdleSize = 2;
        int maxPoolSize = 20;
        ObjectPool<Object> pool = new ObjectPool<>(minIdleSize, maxPoolSize, Object::new, x -> true);
        pool.setDemandFactor(2.0);
        pool.idleTimeout = 100; // 直接设置，绕过 setter 的 max(10000,...) 限制

        // Phase 1：注入高负载采样 → 预热到高水位
        // avgPerPeriod=20 → target=ceil(40)=40 → clamp to maxPoolSize=20
        Arrays.fill(pool.borrowSamples, 20L);
        pool.validNow();
        int highSize = pool.size();
        assertTrue(highSize > minIdleSize, "预热后 size 应大于 minIdleSize，actual: " + highSize);

        // Phase 2：等待 idleTimeout 到期，再清空采样
        Thread.sleep(200);
        Arrays.fill(pool.borrowSamples, 0L);

        // 多次 validNow()：每次淘汰 min(size,8) 个闲置超时对象，并以 targetSize=minIdleSize 不再补充
        for (int i = 0; i < 6; i++) {
            pool.validNow();
        }

        assertTrue(pool.size() <= minIdleSize + 1,
                "负载回落后 pool size 应趋近 minIdleSize，actual: " + pool.size());
    }

    /**
     * 实际 borrow 路径统计验证：高频 borrow/recycle 后，borrowAccumulator 被正确计入采样。
     */
    @SneakyThrows
    @Test
    public void testAdaptiveRefillBorrowCounting() {
        ObjectPool<Object> pool = new ObjectPool<>(1, 10, Object::new, x -> true);
        pool.setDemandFactor(1.0);

        // 高频 borrow/recycle 50 次
        int borrowCount = 50;
        for (int i = 0; i < borrowCount; i++) {
            Object obj = pool.borrow();
            pool.recycle(obj);
        }

        // validNow() 消费 borrowAccumulator 写入 borrowSamples[sampleIndex]
        pool.validNow();

        long sampledInThisPeriod = pool.borrowSamples[(pool.sampleIndex - 1 + ObjectPool.SAMPLE_COUNT) % ObjectPool.SAMPLE_COUNT];
        assertEquals(borrowCount, sampledInThisPeriod,
                "本周期采样值应等于 borrow 次数，actual: " + sampledInThisPeriod);
    }

    @SneakyThrows
    @Test
    public void testCreateObjectNotRetiredBeforeBorrowedStateInitialized() {
        CountDownLatch activateStarted = new CountDownLatch(1);
        CountDownLatch releaseActivate = new CountDownLatch(1);
        ObjectPool<PooledResource> pool = new ObjectPool<>(0, 1,
                () -> new PooledResource(1),
                x -> x.valid,
                x -> {
                    activateStarted.countDown();
                    releaseActivate.await(2, TimeUnit.SECONDS);
                },
                null);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<PooledResource> future = executor.submit(() -> pool.borrow());
            assertTrue(activateStarted.await(2, TimeUnit.SECONDS),
                    "borrow 创建路径应进入 activate");

            for (int i = 0; i < 5; i++) {
                pool.validNow();
            }

            releaseActivate.countDown();
            PooledResource resource = future.get(2, TimeUnit.SECONDS);
            assertFalse(resource.closed.get(), "创建中对象不能被后台扫描误 retire");
            pool.recycle(resource);
        } finally {
            releaseActivate.countDown();
            executor.shutdownNow();
            pool.close();
        }
    }

    @SneakyThrows
    @Test
    public void testCasStateFailureDoesNotUpdateOwnerThreadOrStateTime() {
        ObjectPool.ObjectConf<Object> c = new ObjectPool.ObjectConf<>();
        c.initState(ObjectPool.ObjectConf.IDLE);
        long stateTime = c.stateTime;
        long createTime = c.createTime;
        Thread owner = c.t;

        Thread.sleep(2);

        assertFalse(c.casState(ObjectPool.ObjectConf.RETIRED, ObjectPool.ObjectConf.BORROWED));
        assertEquals(stateTime, c.stateTime, "CAS 失败不应刷新 stateTime");
        assertEquals(createTime, c.createTime, "CAS 失败不应刷新 createTime");
        assertSame(owner, c.t, "CAS 失败不应覆盖 owner thread");
    }

    @SneakyThrows
    @Test
    public void testLookupKeyDoesNotRetainRecycledObject() {
        ObjectPool<Object> pool = new ObjectPool<>(0, 1, Object::new, x -> true);
        Object obj = pool.borrow();
        pool.recycle(obj);

        assertNull(pool.lookupKey.get().instance,
                "lookupKey FastThreadLocal 查找后不应保留被归还对象强引用");
        pool.close();
    }

    @SneakyThrows
    @Test
    public void testLeakDetectionDefaultDoesNotRetireBorrowedObject() {
        ObjectPool<PooledResource> pool = new ObjectPool<>(0, 1,
                () -> new PooledResource(1),
                x -> x.valid);
        pool.leakDetectionThreshold = 1;

        PooledResource borrowed = pool.borrow();
        Thread.sleep(20);
        pool.validNow();

        assertEquals(1, pool.size(), "默认 leak detection 只报警，不应 retire borrowed 对象");
        assertFalse(borrowed.closed.get(), "默认 leak detection 不应关闭 borrowed 对象");
        pool.recycle(borrowed);
        pool.close();
    }

    @SneakyThrows
    @Test
    public void testConcurrencyNoObjectLeak() {
        int maxPoolSize = 3;
        int threads = 8;
        int loops = 30;
        AtomicInteger created = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();

        ObjectPool<Object> pool = new ObjectPool<>(0, maxPoolSize,
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

        // 所有对象归还后，pool size 不应超过 maxPoolSize
        assertTrue(pool.size() <= maxPoolSize,
                "totalCount 不应超过 maxPoolSize，actual: " + pool.size() + ", created: " + created.get());
    }

    @SneakyThrows
    @Test
    public void testStressBorrowRecycleValidateAndCloseRace() {
        AtomicInteger created = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();
        AtomicBoolean closing = new AtomicBoolean(false);
        ObjectPool<PooledResource> pool = new ObjectPool<>(1, 4,
                () -> new PooledResource(created.incrementAndGet()),
                x -> x.valid && !x.closed.get());
        pool.setBorrowTimeout(5000);

        int workers = 8;
        ExecutorService executor = Executors.newFixedThreadPool(workers + 1);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(workers + 1);
        for (int i = 0; i < workers; i++) {
            executor.submit(() -> {
                PooledResource borrowed = null;
                try {
                    start.await();
                    for (int j = 0; j < 100 && !closing.get(); j++) {
                        try {
                            borrowed = pool.borrow();
                            Thread.sleep(1);
                            if (!closing.get()) {
                                pool.recycle(borrowed);
                            }
                            borrowed = null;
                        } catch (Throwable e) {
                            if (!closing.get()) {
                                log.error("Unexpected stress worker error", e);
                                unexpected.incrementAndGet();
                            }
                            return;
                        } finally {
                            if (borrowed != null && !closing.get()) {
                                try {
                                    pool.recycle(borrowed);
                                } catch (Throwable e) {
                                    log.error("Unexpected stress recycle error", e);
                                    unexpected.incrementAndGet();
                                }
                            }
                        }
                    }
                } catch (Throwable e) {
                    if (!closing.get()) {
                        log.error("Unexpected stress setup error", e);
                        unexpected.incrementAndGet();
                    }
                } finally {
                    done.countDown();
                }
            });
        }
        executor.submit(() -> {
            try {
                start.await();
                for (int i = 0; i < 100 && !closing.get(); i++) {
                    pool.validNow();
                    Thread.sleep(1);
                }
            } catch (Throwable e) {
                if (!closing.get()) {
                    log.error("Unexpected stress validator error", e);
                    unexpected.incrementAndGet();
                }
            } finally {
                done.countDown();
            }
        });

        start.countDown();
        Thread.sleep(100);
        closing.set(true);
        pool.close();

        assertTrue(done.await(10, TimeUnit.SECONDS), "stress 线程应在 close 后退出");
        executor.shutdownNow();
        assertEquals(0, unexpected.get(), "borrow/recycle/validNow/close 并发不应出现非预期异常");
        assertTrue(pool.isClosed());
    }

    @SneakyThrows
    static void waitForCondition(Callable<Boolean> condition, long timeoutMs, String message) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.call()) {
                return;
            }
            Thread.sleep(50);
        }
        fail(message);
    }

    static final class PooledResource implements Closeable {
        final int id;
        final AtomicBoolean closed = new AtomicBoolean();
        final AtomicInteger closeCount = new AtomicInteger();
        volatile boolean valid = true;

        PooledResource(int id) {
            this.id = id;
        }

        @Override
        public void close() {
            closeCount.incrementAndGet();
            closed.set(true);
        }
    }
}
