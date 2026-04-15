package org.rx.core;

import io.netty.util.concurrent.FastThreadLocal;
import lombok.Getter;
import lombok.NonNull;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.rx.exception.InvalidException;
import org.rx.exception.TraceHandler;
import org.rx.util.function.BiAction;
import org.rx.util.function.Func;
import org.rx.util.function.PredicateFunc;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import static org.rx.core.Extends.tryClose;

@Slf4j
@ToString
public class ObjectPool<T> extends Disposable {
    static class ObjectConf<T> {
        static final int IDLE = 0, BORROWED = 1, RETIRED = 2;
        IdentityWrapper<T> wrapper;
        final AtomicInteger state = new AtomicInteger(IDLE);
        volatile long stateTime;
        volatile long createTime;
        volatile boolean sharedIdleQueued;
        ObjectConf<T> prevIdle;
        ObjectConf<T> nextIdle;
        ObjectConf<T> prevLive;
        ObjectConf<T> nextLive;
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
            if (createTime == 0) {
                createTime = stateTime;
            }
            state.set(borrowed ? BORROWED : IDLE);
        }

        public boolean casState(int expect, int update) {
            if (update == BORROWED) {
                t = Thread.currentThread();
            }
            stateTime = System.nanoTime();
            if (createTime == 0) {
                createTime = stateTime;
            }
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

        public boolean isExpired(long maxLifetime) {
            return maxLifetime != 0 && createTime != 0
                    && (System.nanoTime() - createTime) / Constants.NANO_TO_MILLIS > maxLifetime;
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
    final FastThreadLocal<ObjectConf<T>> threadLocalCache = new FastThreadLocal<>();
    final AtomicInteger totalCount = new AtomicInteger();
    final AtomicInteger sharedIdleCount = new AtomicInteger();
    final AtomicInteger waitingBorrowers = new AtomicInteger();
    final ReentrantLock idleLock = new ReentrantLock();
    final Condition idleAvailable = idleLock.newCondition();
    final Object liveLock = new Object();
    final TimeoutFuture<?> future;
    @Getter
    volatile int maxPoolSize = 10;
    @Getter
    volatile int minIdleSize;
    @Getter
    volatile long validationPeriod = 30000;
    @Getter
    volatile long borrowTimeout = 10000;
    @Getter
    volatile long idleTimeout = 600000;
    @Getter
    volatile long maxLifetime = 0;
    @Getter
    volatile long leakDetectionThreshold;
    @Getter
    volatile boolean closeObjectOnLeak = true;
    ObjectConf<T> idleHead, idleTail;
    ObjectConf<T> liveHead, scanCursor;
    volatile boolean disposing;

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
        return sharedIdleCount.get();
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

    public void setMaxLifetime(long maxLifetime) {
        this.maxLifetime = maxLifetime == 0 ? 0 : Math.max(30000, maxLifetime);
    }

    public void setLeakDetectionThreshold(long leakDetectionThreshold) {
        this.leakDetectionThreshold = leakDetectionThreshold == 0 ? 0 : Math.max(2000, leakDetectionThreshold);
    }

    public void setMinIdleSize(int minIdleSize) {
        if (minIdleSize < 0) {
            throw new InvalidException("MinIdleSize '{}' must greater than or equal to 0", minIdleSize);
        }
        this.minIdleSize = Math.min(minIdleSize, maxPoolSize);
        if (needsMinIdleMaintain(idleSize(), size())) {
            insureMinIdle();
            triggerMinIdleMaintain();
        }
    }

    public void setMaxPoolSize(int maxPoolSize) {
        if (maxPoolSize < 1) {
            throw new InvalidException("MaxPoolSize '{}' must greater than or equal to 1", maxPoolSize);
        }
        this.maxPoolSize = maxPoolSize;
        if (minIdleSize > maxPoolSize) {
            minIdleSize = maxPoolSize;
        }
    }

    public void setCloseObjectOnLeak(boolean closeObjectOnLeak) {
        this.closeObjectOnLeak = closeObjectOnLeak;
    }

    boolean needsMinIdleMaintain(int idleCount, int total) {
        return !isClosed() && !disposing && minIdleSize > 0 && idleCount < minIdleSize && total < maxPoolSize;
    }

    public ObjectPool(int minIdleSize, int maxPoolSize, Func<T> createHandler, PredicateFunc<T> validateHandler) {
        this(minIdleSize, maxPoolSize, createHandler, validateHandler, null, null);
    }

    public ObjectPool(int minIdleSize, int maxPoolSize,
            @NonNull Func<T> createHandler, @NonNull PredicateFunc<T> validateHandler,
            BiAction<T> activateHandler, BiAction<T> passivateHandler) {
        if (minIdleSize < 0) {
            throw new InvalidException("MinIdleSize '{}' must greater than or equal to 0", minIdleSize);
        }
        if (maxPoolSize < 1) {
            throw new InvalidException("MaxPoolSize '{}' must greater than or equal to 1", maxPoolSize);
        }

        this.maxPoolSize = Math.max(minIdleSize, maxPoolSize);
        this.minIdleSize = Math.min(minIdleSize, this.maxPoolSize);
        this.createHandler = createHandler;
        this.validateHandler = validateHandler;
        this.activateHandler = activateHandler;
        this.passivateHandler = passivateHandler;

        insureMinIdle();
        future = Tasks.timer.setTimeout(this::validNow, d -> validationPeriod, this, Constants.TIMER_PERIOD_FLAG);
    }

    @Override
    protected void dispose() {
        disposing = true;
        future.cancel();
        for (IdentityWrapper<T> key : conf.keySet()) {
            doRetire(key, 0);
        }
    }

    void insureMinIdle() {
        int idle = idleSize();
        int total = size();
        while (needsMinIdleMaintain(idle, total)) {
            ObjectConf<T> c = doCreate();
            if (c != null) {
                recycle(c, false);
                idle = idleSize();
                total = size();
            } else {
                break;
            }
        }
    }

    void insureTargetSize(int target) {
        while (size() < target) {
            ObjectConf<T> c = doCreate();
            if (c != null) {
                recycle(c, false);
            } else {
                break;
            }
        }
    }

    void linkIdleTail0(ObjectConf<T> c) {
        c.prevIdle = idleTail;
        c.nextIdle = null;
        if (idleTail == null) {
            idleHead = c;
        } else {
            idleTail.nextIdle = c;
        }
        idleTail = c;
        c.sharedIdleQueued = true;
        sharedIdleCount.incrementAndGet();
        idleAvailable.signal();
    }

    void unlinkIdle0(ObjectConf<T> c) {
        if (c == null || !c.sharedIdleQueued) {
            return;
        }
        ObjectConf<T> prev = c.prevIdle, next = c.nextIdle;
        if (prev == null) {
            idleHead = next;
        } else {
            prev.nextIdle = next;
        }
        if (next == null) {
            idleTail = prev;
        } else {
            next.prevIdle = prev;
        }
        c.prevIdle = null;
        c.nextIdle = null;
        c.sharedIdleQueued = false;
        sharedIdleCount.decrementAndGet();
    }

    ObjectConf<T> pollIdle0() {
        ObjectConf<T> c = idleTail;
        if (c != null) {
            unlinkIdle0(c);
        }
        return c;
    }

    void offerSharedIdle(ObjectConf<T> c) {
        idleLock.lock();
        try {
            linkIdleTail0(c);
        } finally {
            idleLock.unlock();
        }
    }

    ObjectConf<T> pollSharedIdle() {
        idleLock.lock();
        try {
            return pollIdle0();
        } finally {
            idleLock.unlock();
        }
    }

    ObjectConf<T> pollSharedIdle(long timeout) throws InterruptedException {
        long nanos = TimeUnit.MILLISECONDS.toNanos(timeout);
        idleLock.lockInterruptibly();
        try {
            waitingBorrowers.incrementAndGet();
            try {
                while (true) {
                    ObjectConf<T> c = pollIdle0();
                    if (c != null) {
                        return c;
                    }
                    if (nanos <= 0) {
                        return null;
                    }
                    nanos = idleAvailable.awaitNanos(nanos);
                }
            } finally {
                waitingBorrowers.decrementAndGet();
            }
        } finally {
            idleLock.unlock();
        }
    }

    void linkLive(ObjectConf<T> c) {
        synchronized (liveLock) {
            if (liveHead == null) {
                liveHead = c;
                c.prevLive = c;
                c.nextLive = c;
                scanCursor = c;
                return;
            }
            ObjectConf<T> tail = liveHead.prevLive;
            c.prevLive = tail;
            c.nextLive = liveHead;
            tail.nextLive = c;
            liveHead.prevLive = c;
        }
    }

    void unlinkLive(ObjectConf<T> c) {
        synchronized (liveLock) {
            if (c.prevLive == null || c.nextLive == null) {
                return;
            }
            if (c.nextLive == c) {
                liveHead = null;
                scanCursor = null;
            } else {
                if (liveHead == c) {
                    liveHead = c.nextLive;
                }
                if (scanCursor == c) {
                    scanCursor = c.nextLive;
                }
                c.prevLive.nextLive = c.nextLive;
                c.nextLive.prevLive = c.prevLive;
            }
            c.prevLive = null;
            c.nextLive = null;
        }
    }

    ObjectConf<T> nextScanCandidate() {
        synchronized (liveLock) {
            ObjectConf<T> c = scanCursor;
            if (c == null) {
                return null;
            }
            scanCursor = c.nextLive == null || c.nextLive == c ? c : c.nextLive;
            return c;
        }
    }

    void validNow() {
        if (!validating.compareAndSet(false, true)) {
            return;
        }
        try {
        long localIdleTimeout = idleTimeout;
        long localLeakThreshold = leakDetectionThreshold;
        long localMaxLifetime = maxLifetime;

        int size = size();
        int idleChecked = 0;
        int maxIdleCheck = Math.max(1, Math.min(size, 8));
        int scanBudget = Math.max(maxIdleCheck, Math.min(size, Math.max(16, (int) Math.ceil((double) size / SAMPLE_COUNT))));
        for (int i = 0; i < scanBudget; i++) {
            ObjectConf<T> c = nextScanCandidate();
            if (c == null) {
                break;
            }
            if (c.isRetired() || conf.get(c.wrapper) != c) {
                continue;
            }
            if (c.isBorrowed()) {
                if (c.isLeaked(localLeakThreshold)) {
                    TraceHandler.INSTANCE.saveMetric(Constants.MetricName.OBJECT_POOL_LEAK.name(),
                            String.format("Pool %s owned Object '%s' leaked.\n%s", this, c.wrapper, Reflects.getStackTrace(c.t)));
                    doRetire(c.wrapper, 4);
                }
                continue;
            }

            if (idleChecked >= maxIdleCheck) continue;
            idleChecked++;
            if (!validateHandler.test(c.wrapper.instance)
                    || (idleSize() > minIdleSize && c.isIdleTimeout(localIdleTimeout))
                    || c.isExpired(localMaxLifetime)) {
                if (c.casState(ObjectConf.IDLE, ObjectConf.RETIRED)) {
                    doRetire(c.wrapper, 3);
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

        // 动态目标 = max(minIdleSize, min(maxPoolSize, ceil(avgPerPeriod * demandFactor)))
        double avgPerPeriod = (double) totalBorrows / SAMPLE_COUNT;
        int targetSize = Math.max(minIdleSize, Math.min(maxPoolSize,
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
                    int deficit = Math.min(minIdleSize - idle, maxPoolSize - total);
                    while (deficit-- > 0) {
                        ObjectConf<T> c = doCreate();
                        if (c == null) {
                            break;
                        }
                        recycle(c, false);
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
            if (c.sharedIdleQueued) {
                idleLock.lock();
                try {
                    unlinkIdle0(c);
                } finally {
                    idleLock.unlock();
                }
            }
            unlinkLive(c);
            totalCount.decrementAndGet();

            if (action != 4 || closeObjectOnLeak) {
                tryClose(wrapper);
            }
            if (!disposing) {
                triggerMinIdleMaintain();
            }
            return true;
        }
        return false;
    }

    ObjectConf<T> doCreate() {
        // 使用 CAS 预占位置，解决并发超过 maxPoolSize 的问题
        while (true) {
            int current = totalCount.get();
            if (current >= maxPoolSize) {
                return null;
            }
            if (totalCount.compareAndSet(current, current + 1))
                break;
        }
        IdentityWrapper<T> wrapper = null;
        ObjectConf<T> c = null;
        try {
            wrapper = new IdentityWrapper<>(createHandler.get());
            c = new ObjectConf<>();
            c.wrapper = wrapper;
            ObjectConf<T> prev = conf.putIfAbsent(wrapper, c);
            if (prev != null) {
                // 极少数：已有映射（createHandler 返回了已存在对象）
                totalCount.decrementAndGet();
                throw new InvalidException("Object '{}' has already in this pool", wrapper);
            }
            linkLive(c);
            if (activateHandler != null) {
                activateHandler.accept(wrapper.instance);
            }
            c.setBorrowed(true);
            return c;
        } catch (Throwable e) {
            if (c != null && wrapper != null) {
                doRetire(wrapper, 0);
            } else {
                // createHandler.get() 抛异常时 wrapper 为 null，CAS 预占的位置需要回退
                totalCount.decrementAndGet();
            }
            log.warn("doCreate error", e);
            return null;
        }
    }

    ObjectConf<T> doPoll(long timeout) throws InterruptedException {
        if (timeout <= 0) {
            ObjectConf<T> c;
            while ((c = pollSharedIdle()) != null) {
                if (!c.casState(ObjectConf.IDLE, ObjectConf.BORROWED)) {
                    continue;
                }
                if (activateHandler != null) {
                    try {
                        activateHandler.accept(c.wrapper.instance);
                    } catch (Throwable e) {
                        doRetire(c.wrapper, 0);
                        log.warn("doPoll error", e);
                        continue;
                    }
                }
                return c;
            }
            return null;
        }

        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
        ObjectConf<T> c;
        while (true) {
            long nanos = deadline - System.nanoTime();
            if (nanos <= 0) {
                return null;
            }
            c = pollSharedIdle(TimeUnit.NANOSECONDS.toMillis(nanos));
            if (c == null) {
                return null;
            }
            if (!c.casState(ObjectConf.IDLE, ObjectConf.BORROWED)) {
                continue;
            }
            if (activateHandler != null) {
                try {
                    activateHandler.accept(c.wrapper.instance);
                } catch (Throwable e) {
                    doRetire(c.wrapper, 0);
                    log.warn("doPoll error", e);
                    continue;
                }
            }
            return c;
        }
    }

    public T borrow() throws TimeoutException {
        // L1: ThreadLocal Cache
        ObjectConf<T> c = minIdleSize > 0 ? null : threadLocalCache.get();
        if (c != null) {
            if (conf.get(c.wrapper) == c && c.casState(ObjectConf.IDLE, ObjectConf.BORROWED)) {
                threadLocalCache.set(null); // Clear from L1
                boolean ok = true;
                if (validateHandler.test(c.wrapper.instance)) {
                    if (activateHandler != null) {
                        try {
                            activateHandler.accept(c.wrapper.instance);
                        } catch (Throwable e) {
                            doRetire(c.wrapper, 0);
                            log.warn("borrow L1 error", e);
                            ok = false;
                        }
                    }
                    if (ok) {
                        borrowAccumulator.increment();
                        triggerMinIdleMaintain();
                        return c.wrapper.instance;
                    }
                } else {
                    doRetire(c.wrapper, 1);
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
            c = null;
            try {
                // Try to poll first (fast path) with timeout 0
                c = doPoll(0);
                if (c == null) {
                    if (size() < maxPoolSize) {
                        c = doCreate();
                    }
                    if (c == null) {
                        // 如果 doCreate 因瞬态错误失败（非 maxPoolSize 限制），短暂等待并重试
                        // 如果是 maxPoolSize 限制，等待完整剩余时间
                        long waitTime = (size() < maxPoolSize) ? Math.min(100, remainingTime) : remainingTime;
                        c = doPoll(waitTime);
                    }
                }

                if (c != null) {
                    if (validateHandler.test(c.wrapper.instance)) {
                        borrowAccumulator.increment();
                        triggerMinIdleMaintain();
                        return c.wrapper.instance;
                    }
                    // 必须显式退休掉校验失败的对象
                    doRetire(c.wrapper, 1);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                lastError = e;
                break;
            } catch (Throwable e) {
                if (c != null) {
                    doRetire(c.wrapper, 0);
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
        recycle(c, true);
    }

    void recycle(ObjectConf<T> c, boolean allowThreadLocal) {
        try {
            if (!validateHandler.test(c.wrapper.instance)) {
                doRetire(c.wrapper, 1);
                return;
            }
            if (passivateHandler != null) {
                passivateHandler.accept(c.wrapper.instance);
            }
        } catch (Throwable e) {
            doRetire(c.wrapper, 0);
            throw e;
        }

        if (conf.get(c.wrapper) != c) {
            // doRetire by other thread
            return;
        }
        if (c.casState(ObjectConf.BORROWED, ObjectConf.IDLE)) {
            if (allowThreadLocal && minIdleSize <= 0 && threadLocalCache.get() == null) {
                idleLock.lock();
                try {
                    if (waitingBorrowers.get() == 0) {
                        threadLocalCache.set(c);
                        return;
                    }
                    linkIdleTail0(c);
                    return;
                } finally {
                    idleLock.unlock();
                }
            }

            offerSharedIdle(c);
        } else {
            if (c.isRetired() || conf.get(c.wrapper) != c) {
                return;
            }
            throw new InvalidException("Object '{}' has already in this pool", c.wrapper);
        }
    }
}
