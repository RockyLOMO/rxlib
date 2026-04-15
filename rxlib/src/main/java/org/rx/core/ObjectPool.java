package org.rx.core;

import io.netty.util.concurrent.FastThreadLocal;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.rx.bean.ConcurrentBlockingDeque;
import org.rx.exception.InvalidException;
import org.rx.exception.TraceHandler;
import org.rx.util.function.BiAction;
import org.rx.util.function.Func;
import org.rx.util.function.PredicateFunc;

import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import static org.rx.core.Extends.tryClose;

@Slf4j
@ToString
public class ObjectPool<T> extends Disposable {
    static class ObjectConf<T> {
        static final int IDLE = 0, BORROWED = 1, RETIRED = 2;
        IdentityWrapper<T> wrapper;
        final AtomicInteger state = new AtomicInteger(IDLE);
        volatile long stateTime;
        volatile Thread t;

        public boolean isBorrowed() {
            return state.get() == BORROWED;
        }

        public boolean isRetired() {
            return state.get() == RETIRED;
        }

        public void setBorrowed(boolean borrowed) {
            if (borrowed) {
                t = Thread.currentThread();
            }
            stateTime = System.nanoTime();
            state.set(borrowed ? BORROWED : IDLE);
        }

        public boolean casState(int expect, int update) {
            if (update == BORROWED) {
                t = Thread.currentThread();
            }
            stateTime = System.nanoTime();
            if (state.compareAndSet(expect, update)) {
                return true;
            }
            return false;
        }

        public boolean isIdleTimeout(long idleTimeout) {
            return idleTimeout != 0 && state.get() == IDLE
                    && (System.nanoTime() - stateTime) / Constants.NANO_TO_MILLIS > idleTimeout;
        }

        public boolean isLeaked(long threshold) {
            return threshold != 0 && state.get() == BORROWED
                    && (System.nanoTime() - stateTime) / Constants.NANO_TO_MILLIS > threshold;
        }
    }


    final Func<T> createHandler;
    final PredicateFunc<T> validateHandler;
    final BiAction<T> activateHandler, passivateHandler;
    final ConcurrentHashMap<IdentityWrapper<T>, ObjectConf<T>> conf = new ConcurrentHashMap<>();
    final FastThreadLocal<IdentityWrapper<T>> lookupKey = new FastThreadLocal<IdentityWrapper<T>>() {
        @Override
        protected IdentityWrapper<T> initialValue() {
            return new IdentityWrapper<>();
        }
    };
    final FastThreadLocal<IdentityWrapper<T>> threadLocalCache = new FastThreadLocal<>();
    final AtomicInteger totalCount = new AtomicInteger();
    final ConcurrentBlockingDeque<IdentityWrapper<T>> stack;
    final ConcurrentLinkedDeque<IdentityWrapper<T>> scanQueue = new ConcurrentLinkedDeque<>();
    final TimeoutFuture<?> future;
    @Getter
    final int maxSize;
    @Getter
    volatile int minIdleSize;
    @Getter
    volatile long validationPeriod = 30000;
    @Getter
    volatile long borrowTimeout = 15000;
    @Getter
    volatile long idleTimeout = 600000;
    @Getter
    volatile long leakDetectionThreshold;
    @Getter
    @Setter
    volatile boolean closeObjectOnLeak = true;

    // --- Adaptive refill fields ---
    static final int SAMPLE_COUNT = 12;
    final LongAdder borrowAccumulator = new LongAdder();
    final long[] borrowSamples = new long[SAMPLE_COUNT];
    int sampleIndex = 0;
    final AtomicBoolean minIdleMaintaining = new AtomicBoolean();
    final AtomicBoolean validating = new AtomicBoolean();
    @Getter
    double demandFactor = 2.0;

    public void setDemandFactor(double demandFactor) {
        this.demandFactor = Math.max(0, demandFactor);
    }

    public int size() {
        return totalCount.get();
    }

    public int idleSize() {
        return stack.size();
    }

    public void setValidationPeriod(long validationPeriod) {
        this.validationPeriod = Math.max(250, validationPeriod);
    }

    public void setBorrowTimeout(long borrowTimeout) {
        this.borrowTimeout = Math.max(250, borrowTimeout);
    }

    public void setIdleTimeout(long idleTimeout) {
        this.idleTimeout = idleTimeout == 0 ? 0 : Math.max(10000, idleTimeout);
    }

    public void setLeakDetectionThreshold(long leakDetectionThreshold) {
        this.leakDetectionThreshold = leakDetectionThreshold == 0 ? 0 : Math.max(2000, leakDetectionThreshold);
    }

    public void setMinIdleSize(int minIdleSize) {
        if (minIdleSize < 0) {
            throw new InvalidException("MinIdleSize '{}' must greater than or equal to 0", minIdleSize);
        }
        this.minIdleSize = Math.min(minIdleSize, maxSize);
        triggerMinIdleMaintain();
    }

    boolean needsMinIdleMaintain(int idleCount, int total) {
        return !isClosed() && minIdleSize > 0 && idleCount < minIdleSize && total < maxSize;
    }

    public ObjectPool(int minIdleSize, int maxSize, Func<T> createHandler, PredicateFunc<T> validateHandler) {
        this(minIdleSize, maxSize, createHandler, validateHandler, null, null);
    }

    public ObjectPool(int minIdleSize, int maxSize,
            @NonNull Func<T> createHandler, @NonNull PredicateFunc<T> validateHandler,
            BiAction<T> activateHandler, BiAction<T> passivateHandler) {
        if (minIdleSize < 0) {
            throw new InvalidException("MinIdleSize '{}' must greater than or equal to 0", minIdleSize);
        }
        if (maxSize < 1) {
            throw new InvalidException("MaxSize '{}' must greater than or equal to 1", maxSize);
        }

        this.maxSize = Math.max(minIdleSize, maxSize);
        this.minIdleSize = Math.min(minIdleSize, this.maxSize);
        stack = new ConcurrentBlockingDeque<>(this.maxSize);
        this.createHandler = createHandler;
        this.validateHandler = validateHandler;
        this.activateHandler = activateHandler;
        this.passivateHandler = passivateHandler;

        Tasks.run(this::insureMinIdle);
        future = Tasks.timer.setTimeout(this::validNow, d -> validationPeriod, this, Constants.TIMER_PERIOD_FLAG);
    }

    @Override
    protected void dispose() {
        future.cancel();
        for (IdentityWrapper<T> key : conf.keySet()) {
            doRetire(key, 0);
        }
    }

    void insureMinIdle() {
        int idle = idleSize();
        int total = size();
        while (needsMinIdleMaintain(idle, total)) {
            IdentityWrapper<T> w = doCreate();
            if (w != null) {
                recycle(w);
                idle = idleSize();
                total = size();
            } else {
                break;
            }
        }
    }

    void insureTargetSize(int target) {
        while (size() < target) {
            IdentityWrapper<T> w = doCreate();
            if (w != null) {
                recycle(w);
            } else {
                break;
            }
        }
    }

    void validNow() {
        if (!validating.compareAndSet(false, true)) {
            return;
        }
        try {
        long localIdleTimeout = idleTimeout;
        long localLeakThreshold = leakDetectionThreshold;

        int size = size();
        int idleChecked = 0;
        int maxIdleCheck = Math.max(1, Math.min(size, 8));
        int scanBudget = Math.max(maxIdleCheck, Math.min(size, Math.max(16, (int) Math.ceil((double) size / SAMPLE_COUNT))));
        for (int i = 0; i < scanBudget; i++) {
            IdentityWrapper<T> wrapper = scanQueue.pollFirst();
            if (wrapper == null) {
                break;
            }
            ObjectConf<T> c = conf.get(wrapper);
            if (c == null || c.isRetired()) {
                continue;
            }
            scanQueue.offerLast(wrapper);
            if (c.isBorrowed()) {
                if (c.isLeaked(localLeakThreshold)) {
                    TraceHandler.INSTANCE.saveMetric(Constants.MetricName.OBJECT_POOL_LEAK.name(),
                            String.format("Pool %s owned Object '%s' leaked.\n%s", this, wrapper, Reflects.getStackTrace(c.t)));
                    doRetire(wrapper, 4);
                }
                continue;
            }

            if (idleChecked >= maxIdleCheck) continue;
            idleChecked++;
            if (!validateHandler.test(wrapper.instance)
                    || (idleSize() > minIdleSize && c.isIdleTimeout(localIdleTimeout))) {
                if (c.casState(ObjectConf.IDLE, ObjectConf.RETIRED)) {
                    doRetire(wrapper, 3);
                    size--;
                }
            }
        }

        // 采样：将本周期累积的 borrow 次数写入环形缓冲区
        borrowSamples[sampleIndex] = borrowAccumulator.sumThenReset();
        sampleIndex = (sampleIndex + 1) % SAMPLE_COUNT;

        // 计算最近窗口内总 borrow 数
        long totalBorrows = 0;
        for (long s : borrowSamples) {
            totalBorrows += s;
        }

        // 动态目标 = max(minIdleSize, min(maxSize, ceil(avgPerPeriod * demandFactor)))
        double avgPerPeriod = (double) totalBorrows / SAMPLE_COUNT;
        int targetSize = Math.max(minIdleSize, Math.min(maxSize,
                (int) Math.ceil(avgPerPeriod * demandFactor)));

        log.debug("ObjPool adaptive refill: totalBorrows={}, avgPerPeriod={}, targetSize={}", totalBorrows, avgPerPeriod, targetSize);
        insureTargetSize(targetSize);
        insureMinIdle();
        } finally {
            validating.set(false);
        }
    }

    void triggerMinIdleMaintain() {
        if (!needsMinIdleMaintain(idleSize(), size())) {
            return;
        }
        if (!minIdleMaintaining.compareAndSet(false, true)) {
            return;
        }
        Tasks.run(() -> {
            try {
                while (true) {
                    int idle = idleSize();
                    int total = size();
                    if (!needsMinIdleMaintain(idle, total)) {
                        return;
                    }

                    int created = 0;
                    int deficit = Math.min(minIdleSize - idle, maxSize - total);
                    while (deficit-- > 0) {
                        IdentityWrapper<T> w = doCreate();
                        if (w == null) {
                            break;
                        }
                        recycle(w);
                        created++;
                    }
                    if (created == 0) {
                        return;
                    }
                }
            } finally {
                minIdleMaintaining.set(false);
                if (needsMinIdleMaintain(idleSize(), size())) {
                    triggerMinIdleMaintain();
                }
            }
        });
    }

    // 0 close, 1 recycle validate, 3 idleTimeout, 4 leaked
    boolean doRetire(IdentityWrapper<T> wrapper, int action) {
        ObjectConf<T> c = conf.remove(wrapper);
        if (c != null) {
            c.state.set(ObjectConf.RETIRED);
            stack.remove(wrapper);
            totalCount.decrementAndGet();

            if (action != 4 || closeObjectOnLeak) {
                tryClose(wrapper);
            }
            triggerMinIdleMaintain();
            return true;
        }
        return false;
    }

    IdentityWrapper<T> doCreate() {
        // 使用 CAS 预占位置，解决并发超过 maxSize 的问题
        while (true) {
            int current = totalCount.get();
            if (current >= maxSize) {
                log.warn("ObjPool reject: Reach the maximum");
                return null;
            }
            if (totalCount.compareAndSet(current, current + 1))
                break;
        }
        IdentityWrapper<T> wrapper = null;
        try {
            wrapper = new IdentityWrapper<>(createHandler.get());
            ObjectConf<T> c = new ObjectConf<>();
            c.wrapper = wrapper;
            // 不需要stack.offer(p)，stack是空闲queue
            ObjectConf<T> prev = conf.putIfAbsent(wrapper, c);
            if (prev != null) {
                // 极少数：已有映射（createHandler 返回了已存在对象）
                totalCount.decrementAndGet();
                throw new InvalidException("Object '{}' has already in this pool", wrapper);
            }
            scanQueue.offerLast(wrapper);
            if (activateHandler != null) {
                activateHandler.accept(wrapper.instance);
            }
            c.t = Thread.currentThread();
            c.stateTime = System.nanoTime();
            c.state.set(ObjectConf.BORROWED);
            return wrapper;
        } catch (Throwable e) {
            if (wrapper != null) {
                doRetire(wrapper, 0);
            } else {
                // createHandler.get() 抛异常时 wrapper 为 null，CAS 预占的位置需要回退
                totalCount.decrementAndGet();
            }
            log.warn("doCreate error", e);
            return null;
        }
    }

    IdentityWrapper<T> doPoll(long timeout) throws InterruptedException {
        if (timeout <= 0) {
            IdentityWrapper<T> wrapper;
            while ((wrapper = stack.pollLast()) != null) {
                ObjectConf<T> c = conf.get(wrapper);
                if (c == null || c.isRetired() || !c.casState(ObjectConf.IDLE, ObjectConf.BORROWED)) {
                    continue;
                }
                if (activateHandler != null) {
                    try {
                        activateHandler.accept(wrapper.instance);
                    } catch (Throwable e) {
                        doRetire(wrapper, 0);
                        log.warn("doPoll error", e);
                        continue;
                    }
                }
                return wrapper;
            }
            return null;
        }

        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
        IdentityWrapper<T> wrapper;
        while (true) {
            long nanos = deadline - System.nanoTime();
            if (nanos <= 0) {
                return null;
            }
            wrapper = stack.pollLast(nanos, TimeUnit.NANOSECONDS);
            if (wrapper == null) {
                return null;
            }
            ObjectConf<T> c = conf.get(wrapper);
            if (c == null || c.isRetired() || !c.casState(ObjectConf.IDLE, ObjectConf.BORROWED)) {
                continue;
            }
            if (activateHandler != null) {
                try {
                    activateHandler.accept(wrapper.instance);
                } catch (Throwable e) {
                    doRetire(wrapper, 0);
                    log.warn("doPoll error", e);
                    continue;
                }
            }
            return wrapper;
        }
    }

    public T borrow() throws TimeoutException {
        // L1: ThreadLocal Cache
        IdentityWrapper<T> wrapper = minIdleSize > 0 ? null : threadLocalCache.get();
        if (wrapper != null) {
            ObjectConf<T> c = conf.get(wrapper);
            if (c != null && c.casState(ObjectConf.IDLE, ObjectConf.BORROWED)) {
                threadLocalCache.set(null); // Clear from L1
                boolean ok = true;
                if (validateHandler.test(wrapper.instance)) {
                    if (activateHandler != null) {
                        try {
                            activateHandler.accept(wrapper.instance);
                        } catch (Throwable e) {
                            doRetire(wrapper, 0);
                            log.warn("borrow L1 error", e);
                            ok = false;
                        }
                    }
                    if (ok) {
                        borrowAccumulator.increment();
                        triggerMinIdleMaintain();
                        return wrapper.instance;
                    }
                } else {
                    doRetire(wrapper, 1);
                }
            } else {
                threadLocalCache.set(null); // Retired or borrowed by others
            }
        }

        Throwable lastError = null;
        long beginNanos = System.nanoTime();
        long localBorrowTimeout = borrowTimeout;
        long remainingTime = localBorrowTimeout;
        while (remainingTime > 0) {
            try {
                // Try to poll first (fast path) with timeout 0
                wrapper = doPoll(0);
                if (wrapper == null) {
                    // Stack empty, check if we can create
                    if (size() < maxSize) {
                        wrapper = doCreate();
                    }
                    if (wrapper == null) {
                        // 如果 doCreate 因瞬态错误失败（非 maxSize 限制），短暂等待并重试
                        // 如果是 maxSize 限制，等待完整剩余时间
                        long waitTime = (size() < maxSize) ? Math.min(100, remainingTime) : remainingTime;
                        wrapper = doPoll(waitTime);
                    }
                }

                if (wrapper != null) {
                    if (validateHandler.test(wrapper.instance)) {
                        borrowAccumulator.increment();
                        triggerMinIdleMaintain();
                        return wrapper.instance;
                    }
                    // 必须显式退休掉校验失败的对象
                    doRetire(wrapper, 1);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                lastError = e;
                break;
            } catch (Throwable e) {
                if (wrapper != null) {
                    doRetire(wrapper, 0);
                }
                lastError = e;
            }
            long elapsedMs = (System.nanoTime() - beginNanos) / Constants.NANO_TO_MILLIS;
            remainingTime = localBorrowTimeout - elapsedMs;
        }
        String msg = "borrow timeout";
        if (lastError != null) {
            msg += ": " + lastError.getMessage();
        }
        throw new TimeoutException(msg);
    }

    public void recycle(@NonNull T obj) {
        IdentityWrapper<T> lk = lookupKey.get();
        lk.instance = obj;
        ObjectConf<T> c = conf.get(lk);
        if (c == null) {
            // doRetire by other thread or invalid pool object
            return;
        }
        recycle(c.wrapper);
    }

    void recycle(IdentityWrapper<T> wrapper) {
        try {
            if (!validateHandler.test(wrapper.instance)) {
                doRetire(wrapper, 1);
                return;
            }
            if (passivateHandler != null) {
                passivateHandler.accept(wrapper.instance);
            }
        } catch (Throwable e) {
            doRetire(wrapper, 0);
            throw e;
        }

        ObjectConf<T> c = conf.get(wrapper);
        if (c == null) {
            // doRetire by other thread
            return;
        }
        if (c.casState(ObjectConf.BORROWED, ObjectConf.IDLE)) {
            // L1: Try ThreadLocal Cache if no one waiting on stack
            if (minIdleSize <= 0 && !stack.hasWaiters() && threadLocalCache.get() == null) {
                threadLocalCache.set(wrapper);
                return;
            }

            if (!stack.offer(wrapper)) {
                doRetire(wrapper, 0);
            }
        } else {
            throw new InvalidException("Object '{}' has already in this pool", wrapper);
        }
    }
}
