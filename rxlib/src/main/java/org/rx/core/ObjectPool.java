package org.rx.core;

import io.netty.util.concurrent.FastThreadLocal;
import lombok.Getter;
import lombok.NonNull;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.rx.diagnostic.DiagnosticMetrics;
import org.rx.exception.InvalidException;
import org.rx.util.function.BiAction;
import org.rx.util.function.Func;
import org.rx.util.function.PredicateFunc;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import static org.rx.core.Extends.tryClose;

@Slf4j
@ToString
public class ObjectPool<T> extends Disposable {
    static class ObjectConf<T> {
        static final int IDLE = 0, BORROWED = 1, RETIRED = 2, VALIDATING = 3;

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

        public void initState(int initialState) {
            long now = System.nanoTime();
            stateTime = now;
            if (createTime == 0) {
                createTime = now;
            }
            t = initialState == BORROWED ? Thread.currentThread() : null;
            state.set(initialState);
        }

        public boolean casState(int expect, int update) {
            long now = System.nanoTime();
            if (!state.compareAndSet(expect, update)) {
                return false;
            }
            stateTime = now;
            if (createTime == 0) {
                createTime = now;
            }
            if (update == BORROWED) {
                t = Thread.currentThread();
            } else if (update == IDLE || update == RETIRED || update == VALIDATING) {
                t = null;
            }
            return true;
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
    final LongAdder createdCount = new LongAdder();
    final LongAdder retiredCount = new LongAdder();
    final LongAdder borrowTimeoutCount = new LongAdder();
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
    volatile boolean closeObjectOnLeak = false;
    ObjectConf<T> idleHead, idleTail;
    ObjectConf<T> liveHead, scanCursor;
    volatile boolean disposing;

    static final int SAMPLE_COUNT = 12;
    static final long IDLE_CREATE_FAILURE_BACKOFF_NANOS = TimeUnit.MILLISECONDS.toNanos(100);
    final LongAdder borrowAccumulator = new LongAdder();
    final long[] borrowSamples = new long[SAMPLE_COUNT];
    int sampleIndex = 0;
    final AtomicBoolean minIdleMaintaining = new AtomicBoolean();
    final AtomicBoolean validating = new AtomicBoolean();
    volatile long idleCreateBackoffUntilNanos;
    @Getter
    volatile double demandFactor = 2.0;
    @Getter
    volatile String name;

    public void setDemandFactor(double demandFactor) {
        this.demandFactor = Math.max(0, demandFactor);
    }

    public void setName(String name) {
        this.name = name;
    }

    public int size() {
        return totalCount.get();
    }

    public int idleSize() {
        return sharedIdleCount.get();
    }

    public boolean anyMatch(@NonNull PredicateFunc<T> predicate) {
        for (IdentityWrapper<T> wrapper : conf.keySet()) {
            T instance = wrapper.instance;
            if (instance != null && predicate.test(instance)) {
                return true;
            }
        }
        return false;
    }

    public void forEach(@NonNull Consumer<T> consumer) {
        for (IdentityWrapper<T> wrapper : conf.keySet()) {
            T instance = wrapper.instance;
            if (instance != null) {
                consumer.accept(instance);
            }
        }
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
        if (needsMinIdleMaintain(idleSize(), size())) {
            triggerMinIdleMaintain();
        }
    }

    @Override
    protected void dispose() {
        disposing = true;
        signalBorrowers();
        future.cancel();
        for (IdentityWrapper<T> key : new ArrayList<>(conf.keySet())) {
            doRetire(key, 0);
        }
        signalBorrowers();
    }

    void insureMinIdle() {
        int idle = idleSize();
        int total = size();
        while (needsMinIdleMaintain(idle, total) && !isIdleCreateBackoffActive()) {
            ObjectConf<T> c = doCreateIdle();
            if (c != null) {
                idle = idleSize();
                total = size();
            } else {
                break;
            }
        }
    }

    void insureTargetSize(int target) {
        while (size() < target && !isIdleCreateBackoffActive()) {
            ObjectConf<T> c = doCreateIdle();
            if (c == null) {
                break;
            }
        }
    }

    boolean isIdleCreateBackoffActive() {
        return idleCreateBackoffRemainingNanos() > 0;
    }

    long idleCreateBackoffRemainingNanos() {
        long until = idleCreateBackoffUntilNanos;
        if (until == 0) {
            return 0;
        }
        long remaining = until - System.nanoTime();
        return remaining > 0 ? remaining : 0;
    }

    void markIdleCreateFailure() {
        idleCreateBackoffUntilNanos = System.nanoTime() + IDLE_CREATE_FAILURE_BACKOFF_NANOS;
    }

    void clearIdleCreateFailure() {
        idleCreateBackoffUntilNanos = 0;
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
            if (c.sharedIdleQueued || c.isRetired() || conf.get(c.wrapper) != c) {
                return;
            }
            linkIdleTail0(c);
        } finally {
            idleLock.unlock();
        }
    }

    void removeSharedIdle(ObjectConf<T> c) {
        idleLock.lock();
        try {
            unlinkIdle0(c);
        } finally {
            idleLock.unlock();
        }
    }

    void signalBorrowers() {
        idleLock.lock();
        try {
            idleAvailable.signalAll();
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
                    if (disposing || isClosed()) {
                        return null;
                    }
                    ObjectConf<T> c = pollIdle0();
                    if (c != null) {
                        return c;
                    }
                    if (size() < maxPoolSize) {
                        return null;
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
            List<ObjectConf<T>> snapshot = new ArrayList<>(conf.values());
            for (ObjectConf<T> c : snapshot) {
                if (c == null || c.isRetired() || conf.get(c.wrapper) != c) {
                    continue;
                }
                if (c.isBorrowed()) {
                    if (c.isLeaked(localLeakThreshold)) {
                        if (DiagnosticMetrics.isEnabled()) {
                            DiagnosticMetrics.record(Constants.MetricName.OBJECT_POOL_LEAK.name(), 1D, diagnosticTags());
                        }
                        if (closeObjectOnLeak) {
                            doRetire(c.wrapper, 4);
                        }
                    }
                    continue;
                }
                boolean idleTimedOut = idleSize() > minIdleSize && c.isIdleTimeout(localIdleTimeout);
                boolean expired = c.isExpired(localMaxLifetime);
                if (!c.casState(ObjectConf.IDLE, ObjectConf.VALIDATING)) {
                    continue;
                }
                removeSharedIdle(c);
                try {
                    if (idleTimedOut || expired || !validateHandler.test(c.wrapper.instance)) {
                        if (c.casState(ObjectConf.VALIDATING, ObjectConf.RETIRED)) {
                            doRetire(c.wrapper, 3);
                        }
                    } else if (c.casState(ObjectConf.VALIDATING, ObjectConf.IDLE)) {
                        if (!disposing && !isClosed()) {
                            offerSharedIdle(c);
                        } else {
                            doRetire(c.wrapper, 0);
                        }
                    }
                } catch (Throwable e) {
                    if (c.casState(ObjectConf.VALIDATING, ObjectConf.RETIRED)) {
                        doRetire(c.wrapper, 0);
                    }
                }
            }

            borrowSamples[sampleIndex] = borrowAccumulator.sumThenReset();
            sampleIndex = (sampleIndex + 1) % SAMPLE_COUNT;

            long totalBorrows = 0;
            for (long s : borrowSamples) {
                totalBorrows += s;
            }
            double avgPerPeriod = (double) totalBorrows / SAMPLE_COUNT;
            int targetSize = Math.max(minIdleSize, Math.min(maxPoolSize,
                    (int) Math.ceil(avgPerPeriod * demandFactor)));
            log.debug("ObjPool adaptive refill: totalBorrows={}, avgPerPeriod={}, targetSize={}", totalBorrows, avgPerPeriod, targetSize);
            insureTargetSize(targetSize);
            insureMinIdle();
            recordDiagnosticMetrics(totalBorrows, targetSize);
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
        long backoffNanos = idleCreateBackoffRemainingNanos();
        if (backoffNanos > 0) {
            long delayMillis = Math.max(1, TimeUnit.NANOSECONDS.toMillis(backoffNanos));
            Tasks.setTimeout(() -> {
                minIdleMaintaining.set(false);
                triggerMinIdleMaintain();
            }, delayMillis);
            return;
        }
        Tasks.run(() -> {
            try {
                while (true) {
                    int idle = idleSize();
                    int total = size();
                    if (!needsMinIdleMaintain(idle, total) || isIdleCreateBackoffActive()) {
                        return;
                    }
                    int created = 0;
                    int deficit = Math.min(minIdleSize - idle, maxPoolSize - total);
                    while (deficit-- > 0) {
                        ObjectConf<T> c = doCreateIdle();
                        if (c == null) {
                            break;
                        }
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
            c.stateTime = System.nanoTime();
            if (c.createTime == 0) {
                c.createTime = c.stateTime;
            }
            c.t = null;
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
            signalBorrowers();

            if (action != 4 || closeObjectOnLeak) {
                tryClose(wrapper);
            }
            if (!disposing) {
                triggerMinIdleMaintain();
            }
            retiredCount.increment();
            return true;
        }
        return false;
    }

    boolean reserveSlot() {
        if (disposing || isClosed()) {
            return false;
        }
        while (true) {
            if (disposing || isClosed()) {
                return false;
            }
            int current = totalCount.get();
            if (current >= maxPoolSize) {
                return false;
            }
            if (totalCount.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    void releaseReservedSlot() {
        totalCount.decrementAndGet();
        signalBorrowers();
    }

    ObjectConf<T> doCreate() {
        if (!reserveSlot()) {
            return null;
        }
        IdentityWrapper<T> wrapper = null;
        ObjectConf<T> c = null;
        try {
            wrapper = new IdentityWrapper<>(createHandler.get());
            if (activateHandler != null) {
                activateHandler.accept(wrapper.instance);
            }
            c = new ObjectConf<>();
            c.wrapper = wrapper;
            c.initState(ObjectConf.BORROWED);
            ObjectConf<T> prev = conf.putIfAbsent(wrapper, c);
            if (prev != null) {
                releaseReservedSlot();
                tryClose(wrapper);
                log.warn("Object '{}' has already in this pool", wrapper);
                return null;
            }
            linkLive(c);
            if (disposing || isClosed()) {
                doRetire(wrapper, 0);
                return null;
            }
            createdCount.increment();
            return c;
        } catch (Throwable e) {
            if (c != null && wrapper != null && conf.get(wrapper) == c) {
                doRetire(wrapper, 0);
            } else {
                releaseReservedSlot();
                if (wrapper != null) {
                    tryClose(wrapper);
                }
            }
            log.warn("doCreate error", e);
            return null;
        }
    }

    ObjectConf<T> doCreateIdle() {
        if (isIdleCreateBackoffActive() || !reserveSlot()) {
            return null;
        }
        IdentityWrapper<T> wrapper = null;
        ObjectConf<T> c = null;
        try {
            wrapper = new IdentityWrapper<>(createHandler.get());
            if (!validateHandler.test(wrapper.instance)) {
                releaseReservedSlot();
                tryClose(wrapper);
                markIdleCreateFailure();
                return null;
            }
            if (passivateHandler != null) {
                passivateHandler.accept(wrapper.instance);
            }
            c = new ObjectConf<>();
            c.wrapper = wrapper;
            c.initState(ObjectConf.IDLE);
            ObjectConf<T> prev = conf.putIfAbsent(wrapper, c);
            if (prev != null) {
                releaseReservedSlot();
                tryClose(wrapper);
                markIdleCreateFailure();
                log.warn("Object '{}' has already in this pool", wrapper);
                return null;
            }
            linkLive(c);
            if (disposing || isClosed()) {
                doRetire(wrapper, 0);
                return null;
            }
            createdCount.increment();
            clearIdleCreateFailure();
            offerSharedIdle(c);
            return c;
        } catch (Throwable e) {
            if (c != null && wrapper != null && conf.get(wrapper) == c) {
                doRetire(wrapper, 0);
            } else {
                releaseReservedSlot();
                if (wrapper != null) {
                    tryClose(wrapper);
                }
            }
            if (!disposing && !isClosed()) {
                markIdleCreateFailure();
            }
            log.warn("doCreateIdle error", e);
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
        checkBorrowable();
        ObjectConf<T> c = minIdleSize > 0 ? null : threadLocalCache.get();
        if (c != null) {
            threadLocalCache.set(null);
            if (conf.get(c.wrapper) == c && c.casState(ObjectConf.IDLE, ObjectConf.BORROWED)) {
                removeSharedIdle(c);
                try {
                    if (!validateHandler.test(c.wrapper.instance)) {
                        doRetire(c.wrapper, 1);
                    } else {
                        if (activateHandler != null) {
                            activateHandler.accept(c.wrapper.instance);
                        }
                        borrowAccumulator.increment();
                        triggerMinIdleMaintain();
                        return c.wrapper.instance;
                    }
                } catch (Throwable e) {
                    doRetire(c.wrapper, 0);
                    log.warn("borrow L1 error", e);
                }
            }
        }

        Throwable lastError = null;
        long beginNanos = System.nanoTime();
        long localBorrowTimeout = borrowTimeout;
        long remainingTime = localBorrowTimeout;
        while (remainingTime > 0) {
            checkBorrowable();
            c = null;
            try {
                boolean createAttempted = false;
                c = doPoll(0);
                if (c == null) {
                    if (size() < maxPoolSize) {
                        createAttempted = true;
                        c = doCreate();
                    }
                    if (c == null) {
                        if (createAttempted && size() < maxPoolSize && !disposing && !isClosed()) {
                            backoffCreateFailure(remainingTime);
                        } else {
                            c = doPoll(remainingTime);
                        }
                    }
                }

                if (c != null) {
                    checkBorrowable();
                    if (validateHandler.test(c.wrapper.instance)) {
                        borrowAccumulator.increment();
                        triggerMinIdleMaintain();
                        return c.wrapper.instance;
                    }
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
        borrowTimeoutCount.increment();
        throw new TimeoutException(msg);
    }

    void backoffCreateFailure(long remainingTime) throws InterruptedException {
        long waitTime = Math.min(100, Math.max(1, remainingTime));
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(waitTime));
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
    }

    void checkBorrowable() throws TimeoutException {
        checkNotClosed();
        if (disposing) {
            throw new TimeoutException("pool disposing");
        }
    }

    public void recycle(@NonNull T obj) {
        IdentityWrapper<T> lk = lookupKey.get();
        ObjectConf<T> c;
        try {
            lk.instance = obj;
            c = conf.get(lk);
        } finally {
            lk.instance = null;
        }
        if (c == null) {
            return;
        }
        recycle(c, true);
    }

    void recycle(ObjectConf<T> c, boolean allowThreadLocal) {
        if (conf.get(c.wrapper) != c) {
            return;
        }
        if (!c.casState(ObjectConf.BORROWED, ObjectConf.VALIDATING)) {
            if (c.isRetired() || conf.get(c.wrapper) != c) {
                return;
            }
            throw new InvalidException("Object '{}' has already in this pool", c.wrapper);
        }
        try {
            if (!validateHandler.test(c.wrapper.instance)) {
                if (c.casState(ObjectConf.VALIDATING, ObjectConf.RETIRED)) {
                    doRetire(c.wrapper, 1);
                }
                return;
            }
            if (passivateHandler != null) {
                passivateHandler.accept(c.wrapper.instance);
            }
        } catch (Throwable e) {
            if (c.casState(ObjectConf.VALIDATING, ObjectConf.RETIRED)) {
                doRetire(c.wrapper, 0);
            }
            throw e;
        }

        if (conf.get(c.wrapper) != c) {
            return;
        }
        if (c.casState(ObjectConf.VALIDATING, ObjectConf.IDLE)) {
            offerSharedIdle(c);
            if (allowThreadLocal && minIdleSize <= 0 && threadLocalCache.get() == null) {
                threadLocalCache.set(c);
            }
        } else {
            if (c.isRetired() || conf.get(c.wrapper) != c) {
                return;
            }
            throw new InvalidException("Object '{}' has already in this pool", c.wrapper);
        }
    }

    void recordDiagnosticMetrics(long borrowsInWindow, int targetSize) {
        if (!DiagnosticMetrics.isEnabled()) {
            return;
        }
        String tags = diagnosticTags();
        int total = size();
        int idle = idleSize();
        int active = Math.max(0, total - idle);
        DiagnosticMetrics.record("rx.object_pool.size.count", total, tags);
        DiagnosticMetrics.record("rx.object_pool.idle.count", idle, tags);
        DiagnosticMetrics.record("rx.object_pool.active.count", active, tags);
        DiagnosticMetrics.record("rx.object_pool.waiting.count", waitingBorrowers.get(), tags);
        DiagnosticMetrics.record("rx.object_pool.borrow.window.count", borrowsInWindow, tags);
        DiagnosticMetrics.record("rx.object_pool.target.count", targetSize, tags);
        DiagnosticMetrics.record("rx.object_pool.target.total.count", targetSize, tags);
        DiagnosticMetrics.record("rx.object_pool.created.count", createdCount.sum(), tags);
        DiagnosticMetrics.record("rx.object_pool.retired.count", retiredCount.sum(), tags);
        DiagnosticMetrics.record("rx.object_pool.borrow.timeout.count", borrowTimeoutCount.sum(), tags);
    }

    private String diagnosticTags() {
        StringBuilder b = new StringBuilder(96)
                .append("pool=").append(Integer.toHexString(System.identityHashCode(this)))
                .append(",handler=").append(sanitizeMetricTag(createHandler.getClass().getName()));
        String localName = name;
        if (localName != null && localName.length() != 0) {
            b.append(",name=").append(sanitizeMetricTag(localName));
        }
        return b.toString();
    }

    private static String sanitizeMetricTag(String value) {
        if (value == null || value.length() == 0) {
            return "unknown";
        }
        return value.replace(',', '_').replace('\r', ' ').replace('\n', ' ');
    }
}
