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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import static org.rx.core.Extends.tryClose;

@Slf4j
@ToString
public class ObjectPool<T> extends Disposable {
    static final int RETIRE_CLOSE = 0;
    static final int RETIRE_VALIDATE = 1;
    static final int RETIRE_IDLE_TIMEOUT = 3;
    static final int RETIRE_LEAKED = 4;
    static final int RETIRE_EXPIRED = 5;
    static final int RETIRE_OVER_CAPACITY = 6;

    public static final class Lease<T> implements AutoCloseable {
        private final ObjectPool<T> pool;
        private final ObjectConf<T> conf;
        private final long generation;
        private final T value;
        private final AtomicBoolean released = new AtomicBoolean();

        Lease(ObjectPool<T> pool, ObjectConf<T> conf, long generation, T value) {
            this.pool = pool;
            this.conf = conf;
            this.generation = generation;
            this.value = value;
        }

        public T get() {
            if (released.get()) {
                throw new IllegalStateException("ObjectPool lease has already been released");
            }
            return value;
        }

        public boolean isReleased() {
            return released.get();
        }

        public void recycle() {
            if (!released.compareAndSet(false, true)) {
                return;
            }
            pool.recycleLease(conf, generation);
        }

        public void discard() {
            if (!released.compareAndSet(false, true)) {
                return;
            }
            pool.discardLease(conf, generation);
        }

        @Override
        public void close() {
            recycle();
        }
    }

    static class ObjectConf<T> {
        static final int IDLE = 0, BORROWED = 1, RETIRED = 2, VALIDATING = 3, CREATING = 4;
        private static final int STATE_BITS = 3;
        private static final long STATE_MASK = (1L << STATE_BITS) - 1L;

        static long pack(int state, long generation) {
            return (generation << STATE_BITS) | (state & STATE_MASK);
        }

        static int stateOf(long stateAndGen) {
            return (int) (stateAndGen & STATE_MASK);
        }

        static long genOf(long stateAndGen) {
            return stateAndGen >>> STATE_BITS;
        }

        IdentityWrapper<T> wrapper;
        final AtomicLong stateAndGen = new AtomicLong(pack(IDLE, 0L));
        volatile long stateTime;
        volatile long createTime;
        volatile long idleSinceTime;
        volatile long borrowTime;
        volatile boolean sharedIdleQueued;
        ObjectConf<T> prevIdle;
        ObjectConf<T> nextIdle;
        volatile Thread t;

        int getState() {
            return stateOf(stateAndGen.get());
        }

        long getGeneration() {
            return genOf(stateAndGen.get());
        }

        long getStateAndGen() {
            return stateAndGen.get();
        }

        public boolean isBorrowed() {
            return getState() == BORROWED;
        }

        public boolean isRetired() {
            return getState() == RETIRED;
        }

        public void initState(int initialState) {
            long now = System.nanoTime();
            stateTime = now;
            if (createTime == 0L) {
                createTime = now;
            }
            long gen = initialState == BORROWED ? 1L : 0L;
            if (initialState == IDLE) {
                idleSinceTime = now;
                borrowTime = 0L;
                t = null;
            } else if (initialState == BORROWED) {
                borrowTime = now;
                t = Thread.currentThread();
            } else {
                borrowTime = 0L;
                t = null;
            }
            stateAndGen.set(pack(initialState, gen));
        }

        public boolean casState(int expect, int update) {
            return casState(expect, update, true);
        }

        boolean casState(int expect, int update, boolean refreshIdleSince) {
            while (true) {
                long cur = stateAndGen.get();
                if (stateOf(cur) != expect) {
                    return false;
                }
                long curGen = genOf(cur);
                long nextGen = update == BORROWED ? curGen + 1L : curGen;
                if (stateAndGen.compareAndSet(cur, pack(update, nextGen))) {
                    onStateTransition(expect, update, refreshIdleSince);
                    return true;
                }
            }
        }

        boolean casStateForGen(long expectedGen, int expectState, int updateState, boolean refreshIdleSince) {
            long expected = pack(expectState, expectedGen);
            long nextGen = updateState == BORROWED ? expectedGen + 1L : expectedGen;
            if (!stateAndGen.compareAndSet(expected, pack(updateState, nextGen))) {
                return false;
            }
            onStateTransition(expectState, updateState, refreshIdleSince);
            return true;
        }

        boolean casStateWithGen(long expectedStateAndGen, int updateState) {
            long expectedGen = genOf(expectedStateAndGen);
            int expectState = stateOf(expectedStateAndGen);
            long nextGen = updateState == BORROWED ? expectedGen + 1L : expectedGen;
            if (!stateAndGen.compareAndSet(expectedStateAndGen, pack(updateState, nextGen))) {
                return false;
            }
            onStateTransition(expectState, updateState, false);
            return true;
        }

        int markRetiredAndGetPrevState() {
            while (true) {
                long cur = stateAndGen.get();
                int prevState = stateOf(cur);
                if (prevState == RETIRED) {
                    return RETIRED;
                }
                long next = pack(RETIRED, genOf(cur));
                if (stateAndGen.compareAndSet(cur, next)) {
                    onStateTransition(prevState, RETIRED, false);
                    return prevState;
                }
            }
        }

        private void onStateTransition(int expect, int update, boolean refreshIdleSince) {
            long now = System.nanoTime();
            stateTime = now;
            if (createTime == 0L) {
                createTime = now;
            }
            if (update == BORROWED) {
                borrowTime = now;
                t = Thread.currentThread();
            } else {
                if (expect == BORROWED || update == RETIRED) {
                    borrowTime = 0L;
                    t = null;
                } else if (update == IDLE || update == VALIDATING) {
                    t = null;
                }
                if (update == IDLE && (refreshIdleSince || idleSinceTime == 0L)) {
                    idleSinceTime = now;
                }
            }
        }

        public boolean isIdleTimeout(long idleTimeout) {
            if (idleTimeout == 0L) {
                return false;
            }
            int st = getState();
            if (st != IDLE && st != VALIDATING) {
                return false;
            }
            long since = idleSinceTime;
            return since != 0L && (System.nanoTime() - since) / Constants.NANO_TO_MILLIS > idleTimeout;
        }

        public boolean isLeaked(long threshold) {
            if (threshold == 0L) {
                return false;
            }
            long sg1 = stateAndGen.get();
            if (stateOf(sg1) != BORROWED) {
                return false;
            }
            long bt = borrowTime;
            long sg2 = stateAndGen.get();
            return sg1 == sg2 && bt != 0L && (System.nanoTime() - bt) / Constants.NANO_TO_MILLIS > threshold;
        }

        public boolean isExpired(long maxLifetime) {
            return maxLifetime != 0L && createTime != 0L
                    && (System.nanoTime() - createTime) / Constants.NANO_TO_MILLIS > maxLifetime;
        }
    }

    static final FastThreadLocal<IdentityWrapper<Object>> lookupKey = new FastThreadLocal<IdentityWrapper<Object>>() {
        @Override
        protected IdentityWrapper<Object> initialValue() {
            return new IdentityWrapper<>();
        }
    };

    final Func<T> createHandler;
    final PredicateFunc<T> validateHandler;
    final BiAction<T> activateHandler, passivateHandler;
    final ConcurrentHashMap<IdentityWrapper<T>, ObjectConf<T>> conf = new ConcurrentHashMap<>();
    final AtomicInteger totalCount = new AtomicInteger();
    final AtomicInteger sharedIdleCount = new AtomicInteger();
    final AtomicInteger borrowedCount = new AtomicInteger();
    final AtomicInteger waitingBorrowers = new AtomicInteger();
    final LongAdder createdCount = new LongAdder();
    final LongAdder retiredCount = new LongAdder();
    final LongAdder borrowTimeoutCount = new LongAdder();
    final LongAdder maintainRejectedCount = new LongAdder();
    final ReentrantLock idleLock = new ReentrantLock();
    final Condition idleAvailable = idleLock.newCondition();
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
    volatile boolean disposing;

    static final int SAMPLE_COUNT = 12;
    static final long IDLE_CREATE_FAILURE_BACKOFF_NANOS = TimeUnit.MILLISECONDS.toNanos(100);
    final LongAdder borrowAccumulator = new LongAdder();
    final long[] borrowSamples = new long[SAMPLE_COUNT];
    final AtomicInteger periodPeakDemand = new AtomicInteger();
    final int[] peakDemandSamples = new int[SAMPLE_COUNT];
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

    public int borrowedSize() {
        return borrowedCount.get();
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
        int prev = this.maxPoolSize;
        this.maxPoolSize = maxPoolSize;
        if (minIdleSize > maxPoolSize) {
            minIdleSize = maxPoolSize;
        }
        if (maxPoolSize > prev) {
            signalBorrowers();
            if (needsMinIdleMaintain(idleSize(), size())) {
                triggerMinIdleMaintain();
            }
        } else if (maxPoolSize < prev) {
            trimExcessIdle();
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
        for (ObjectConf<T> c : new ArrayList<>(conf.values())) {
            doRetire(c, RETIRE_CLOSE);
        }
        signalBorrowers();
    }

    void onEnterBorrowed() {
        int active = borrowedCount.incrementAndGet();
        recordConcurrentDemand(active + waitingBorrowers.get());
    }

    void onLeaveBorrowed() {
        borrowedCount.decrementAndGet();
    }

    void recordConcurrentDemand(int demand) {
        while (true) {
            int cur = periodPeakDemand.get();
            if (demand <= cur) {
                return;
            }
            if (periodPeakDemand.compareAndSet(cur, demand)) {
                return;
            }
        }
    }

    void trimExcessIdle() {
        while (!disposing && !isClosed() && size() > maxPoolSize) {
            ObjectConf<T> c = pollSharedIdle();
            if (c == null) {
                break;
            }
            if (c.casState(ObjectConf.IDLE, ObjectConf.RETIRED, false)) {
                doRetire(c, RETIRE_OVER_CAPACITY);
            }
        }
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
        int clamped = Math.min(target, maxPoolSize);
        while (!disposing && !isClosed() && size() < clamped && !isIdleCreateBackoffActive()) {
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
            if (c.sharedIdleQueued || c.isRetired() || disposing || isClosed() || conf.get(c.wrapper) != c) {
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
        return pollSharedIdleNanos(TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeout)));
    }

    ObjectConf<T> pollSharedIdleNanos(long nanos) throws InterruptedException {
        idleLock.lockInterruptibly();
        try {
            int waiters = waitingBorrowers.incrementAndGet();
            recordConcurrentDemand(borrowedCount.get() + waiters);
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
                    if (nanos <= 0L) {
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

    void validNow() {
        if (!validating.compareAndSet(false, true)) {
            return;
        }
        try {
            if (disposing || isClosed()) {
                return;
            }
            long localIdleTimeout = idleTimeout;
            long localLeakThreshold = leakDetectionThreshold;
            long localMaxLifetime = maxLifetime;
            List<ObjectConf<T>> snapshot = new ArrayList<>(conf.values());
            for (ObjectConf<T> c : snapshot) {
                if (c == null || c.isRetired() || conf.get(c.wrapper) != c) {
                    continue;
                }
                long sg1 = c.getStateAndGen();
                int state = ObjectConf.stateOf(sg1);
                if (state == ObjectConf.BORROWED) {
                    if (localLeakThreshold > 0L) {
                        long bt = c.borrowTime;
                        long sg2 = c.getStateAndGen();
                        if (sg1 == sg2 && bt != 0L
                                && (System.nanoTime() - bt) / Constants.NANO_TO_MILLIS > localLeakThreshold) {
                            if (DiagnosticMetrics.isEnabled()) {
                                DiagnosticMetrics.record(Constants.MetricName.OBJECT_POOL_LEAK.name(), 1D, diagnosticTags());
                            }
                            if (closeObjectOnLeak && c.casStateWithGen(sg1, ObjectConf.RETIRED)) {
                                onLeaveBorrowed();
                                doRetire(c, RETIRE_LEAKED);
                            }
                        }
                    }
                    continue;
                }
                if (state != ObjectConf.IDLE) {
                    continue;
                }
                if (!c.casState(ObjectConf.IDLE, ObjectConf.VALIDATING, false)) {
                    continue;
                }
                removeSharedIdle(c);
                try {
                    boolean expired = c.isExpired(localMaxLifetime);
                    boolean idleTimedOut = !expired && idleSize() >= minIdleSize && c.isIdleTimeout(localIdleTimeout);
                    boolean overCapacity = size() > maxPoolSize;
                    if (expired) {
                        if (c.casState(ObjectConf.VALIDATING, ObjectConf.RETIRED, false)) {
                            doRetire(c, RETIRE_EXPIRED);
                        }
                    } else if (idleTimedOut) {
                        if (c.casState(ObjectConf.VALIDATING, ObjectConf.RETIRED, false)) {
                            doRetire(c, RETIRE_IDLE_TIMEOUT);
                        }
                    } else if (overCapacity) {
                        if (c.casState(ObjectConf.VALIDATING, ObjectConf.RETIRED, false)) {
                            doRetire(c, RETIRE_OVER_CAPACITY);
                        }
                    } else if (!validateHandler.test(c.wrapper.instance)) {
                        if (c.casState(ObjectConf.VALIDATING, ObjectConf.RETIRED, false)) {
                            doRetire(c, RETIRE_VALIDATE);
                        }
                    } else if (c.casState(ObjectConf.VALIDATING, ObjectConf.IDLE, false)) {
                        if (!disposing && !isClosed()) {
                            offerSharedIdle(c);
                            if (disposing || isClosed()) {
                                doRetire(c, RETIRE_CLOSE);
                            }
                        } else {
                            doRetire(c, RETIRE_CLOSE);
                        }
                    }
                } catch (Throwable e) {
                    if (c.casState(ObjectConf.VALIDATING, ObjectConf.RETIRED, false)) {
                        doRetire(c, RETIRE_CLOSE);
                    }
                }
            }

            if (disposing || isClosed()) {
                return;
            }

            borrowSamples[sampleIndex] = borrowAccumulator.sumThenReset();
            int currentDemand = borrowedCount.get() + waitingBorrowers.get();
            int peakInPeriod = Math.max(periodPeakDemand.getAndSet(currentDemand), currentDemand);
            peakDemandSamples[sampleIndex] = peakInPeriod;
            sampleIndex = (sampleIndex + 1) % SAMPLE_COUNT;

            long totalBorrows = 0L;
            long totalPeakDemand = 0L;
            for (int i = 0; i < SAMPLE_COUNT; i++) {
                totalBorrows += borrowSamples[i];
                totalPeakDemand += peakDemandSamples[i];
            }
            double avgPeakDemand = (double) totalPeakDemand / SAMPLE_COUNT;
            double effectiveDemand = Math.max(peakInPeriod, avgPeakDemand);
            double scaledDemand = effectiveDemand * demandFactor;
            int rawTarget = peakInPeriod > 0 ? (int) Math.ceil(scaledDemand) : (int) Math.floor(scaledDemand);
            int targetSize = Math.max(minIdleSize, Math.min(maxPoolSize, rawTarget));
            if (log.isDebugEnabled()) {
                log.debug("ObjPool adaptive refill: peakInPeriod={}, avgPeak={}, totalBorrows={}, targetSize={}",
                        peakInPeriod, avgPeakDemand, totalBorrows, targetSize);
            }
            trimExcessIdle();
            insureTargetSize(targetSize);
            insureMinIdle();
            recordDiagnosticMetrics(totalBorrows, peakInPeriod, targetSize);
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
            long delayMillis = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(backoffNanos));
            try {
                Tasks.setTimeout(() -> {
                    minIdleMaintaining.set(false);
                    triggerMinIdleMaintain();
                }, delayMillis);
            } catch (Throwable e) {
                minIdleMaintaining.set(false);
                maintainRejectedCount.increment();
                log.warn("Schedule minIdle maintain retry failed", e);
            }
            return;
        }
        boolean submitted = false;
        try {
            submitted = Tasks.tryExecute(this::runMinIdleMaintain);
        } catch (Throwable e) {
            log.warn("Submit minIdle maintain task failed", e);
        }
        if (!submitted) {
            minIdleMaintaining.set(false);
            maintainRejectedCount.increment();
            if (log.isDebugEnabled()) {
                log.debug("Skip minIdle maintain because worker pool rejected non-blocking submit");
            }
        }
    }

    private void runMinIdleMaintain() {
        boolean madeProgress = false;
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
                    madeProgress = true;
                }
                if (created == 0) {
                    return;
                }
            }
        } finally {
            minIdleMaintaining.set(false);
            if (needsMinIdleMaintain(idleSize(), size()) && (madeProgress || isIdleCreateBackoffActive())) {
                triggerMinIdleMaintain();
            }
        }
    }

    boolean doRetire(IdentityWrapper<T> wrapper, int action) {
        ObjectConf<T> c = conf.get(wrapper);
        return c != null && doRetire(c, action);
    }

    boolean doRetire(ObjectConf<T> c, int action) {
        if (c == null || c.wrapper == null) {
            return false;
        }
        if (!conf.remove(c.wrapper, c)) {
            return false;
        }
        int prevState = c.markRetiredAndGetPrevState();
        if (prevState == ObjectConf.BORROWED) {
            onLeaveBorrowed();
        }
        removeSharedIdle(c);
        totalCount.decrementAndGet();
        signalBorrowers();

        if (action != RETIRE_LEAKED || closeObjectOnLeak) {
            tryClose(c.wrapper);
        }
        if (!disposing && !isClosed()) {
            triggerMinIdleMaintain();
        }
        retiredCount.increment();
        return true;
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
        boolean registered = false;
        boolean duplicate = false;
        try {
            T instance = createHandler.get();
            if (instance == null) {
                releaseReservedSlot();
                log.warn("doCreate returned null instance");
                return null;
            }
            wrapper = new IdentityWrapper<>(instance);
            c = new ObjectConf<>();
            c.wrapper = wrapper;
            c.initState(ObjectConf.CREATING);
            ObjectConf<T> prev = conf.putIfAbsent(wrapper, c);
            if (prev != null) {
                duplicate = true;
                releaseReservedSlot();
                log.warn("Object '{}' has already in this pool", wrapper);
                return null;
            }
            registered = true;
            if (disposing || isClosed()) {
                doRetire(c, RETIRE_CLOSE);
                return null;
            }
            if (activateHandler != null) {
                activateHandler.accept(instance);
            }
            if (!c.casState(ObjectConf.CREATING, ObjectConf.BORROWED, false)) {
                return null;
            }
            onEnterBorrowed();
            if (disposing || isClosed()) {
                doRetire(c, RETIRE_CLOSE);
                return null;
            }
            createdCount.increment();
            return c;
        } catch (Throwable e) {
            if (registered) {
                doRetire(c, RETIRE_CLOSE);
            } else {
                releaseReservedSlot();
                if (!duplicate && wrapper != null) {
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
        boolean registered = false;
        boolean duplicate = false;
        try {
            T instance = createHandler.get();
            if (instance == null) {
                releaseReservedSlot();
                markIdleCreateFailure();
                log.warn("doCreateIdle returned null instance");
                return null;
            }
            wrapper = new IdentityWrapper<>(instance);
            c = new ObjectConf<>();
            c.wrapper = wrapper;
            c.initState(ObjectConf.CREATING);
            ObjectConf<T> prev = conf.putIfAbsent(wrapper, c);
            if (prev != null) {
                duplicate = true;
                releaseReservedSlot();
                markIdleCreateFailure();
                log.warn("Object '{}' has already in this pool", wrapper);
                return null;
            }
            registered = true;
            if (disposing || isClosed()) {
                doRetire(c, RETIRE_CLOSE);
                return null;
            }
            if (!validateHandler.test(instance)) {
                doRetire(c, RETIRE_VALIDATE);
                markIdleCreateFailure();
                return null;
            }
            if (passivateHandler != null) {
                passivateHandler.accept(instance);
            }
            if (!c.casState(ObjectConf.CREATING, ObjectConf.IDLE, true)) {
                return null;
            }
            if (disposing || isClosed()) {
                doRetire(c, RETIRE_CLOSE);
                return null;
            }
            createdCount.increment();
            clearIdleCreateFailure();
            offerSharedIdle(c);
            if (disposing || isClosed()) {
                doRetire(c, RETIRE_CLOSE);
                return null;
            }
            return c;
        } catch (Throwable e) {
            if (registered) {
                doRetire(c, RETIRE_CLOSE);
            } else {
                releaseReservedSlot();
                if (!duplicate && wrapper != null) {
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
        return doPollNanos(TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeout)));
    }

    ObjectConf<T> doPollNanos(long timeoutNanos) throws InterruptedException {
        if (timeoutNanos <= 0L) {
            ObjectConf<T> c;
            while ((c = pollSharedIdle()) != null) {
                if (!c.casState(ObjectConf.IDLE, ObjectConf.BORROWED, false)) {
                    continue;
                }
                onEnterBorrowed();
                if (activateHandler != null) {
                    try {
                        activateHandler.accept(c.wrapper.instance);
                    } catch (Throwable e) {
                        doRetire(c, RETIRE_CLOSE);
                        log.warn("doPoll error", e);
                        continue;
                    }
                }
                return c;
            }
            return null;
        }

        long deadlineNanos = System.nanoTime() + timeoutNanos;
        while (true) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0L) {
                return null;
            }
            ObjectConf<T> c = pollSharedIdleNanos(remainingNanos);
            if (c == null) {
                return null;
            }
            if (!c.casState(ObjectConf.IDLE, ObjectConf.BORROWED, false)) {
                continue;
            }
            onEnterBorrowed();
            if (activateHandler != null) {
                try {
                    activateHandler.accept(c.wrapper.instance);
                } catch (Throwable e) {
                    doRetire(c, RETIRE_CLOSE);
                    log.warn("doPoll error", e);
                    continue;
                }
            }
            return c;
        }
    }

    public T borrow() throws TimeoutException {
        return borrowConf().wrapper.instance;
    }

    public Lease<T> borrowLease() throws TimeoutException {
        ObjectConf<T> c = borrowConf();
        return new Lease<>(this, c, c.getGeneration(), c.wrapper.instance);
    }

    private ObjectConf<T> borrowConf() throws TimeoutException {
        checkBorrowable();
        Throwable lastError = null;
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(borrowTimeout);
        while (true) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0L) {
                break;
            }
            checkBorrowable();
            ObjectConf<T> c = null;
            try {
                boolean createAttempted = false;
                c = doPollNanos(0L);
                if (c == null) {
                    if (size() < maxPoolSize) {
                        createAttempted = true;
                        c = doCreate();
                    }
                    if (c == null) {
                        remainingNanos = deadlineNanos - System.nanoTime();
                        if (remainingNanos <= 0L) {
                            break;
                        }
                        if (createAttempted && size() < maxPoolSize && !disposing && !isClosed()) {
                            backoffCreateFailureNanos(remainingNanos);
                        } else {
                            c = doPollNanos(remainingNanos);
                        }
                    }
                }

                if (c != null) {
                    checkBorrowable();
                    if (validateHandler.test(c.wrapper.instance)) {
                        borrowAccumulator.increment();
                        triggerMinIdleMaintain();
                        return c;
                    }
                    doRetire(c, RETIRE_VALIDATE);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                lastError = e;
                break;
            } catch (Throwable e) {
                if (c != null) {
                    doRetire(c, RETIRE_CLOSE);
                }
                lastError = e;
            }
        }
        String msg = "borrow timeout";
        if (lastError != null) {
            msg += ": " + lastError.getMessage();
        }
        borrowTimeoutCount.increment();
        throw new TimeoutException(msg);
    }

    void backoffCreateFailure(long remainingTime) throws InterruptedException {
        backoffCreateFailureNanos(TimeUnit.MILLISECONDS.toNanos(Math.max(1L, remainingTime)));
    }

    void backoffCreateFailureNanos(long remainingNanos) throws InterruptedException {
        long waitNanos = Math.min(TimeUnit.MILLISECONDS.toNanos(100L), Math.max(1L, remainingNanos));
        LockSupport.parkNanos(waitNanos);
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
        IdentityWrapper<Object> lk = lookupKey.get();
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
        recycle(c);
    }

    void recycle(ObjectConf<T> c) {
        if (conf.get(c.wrapper) != c) {
            return;
        }
        if (!c.casState(ObjectConf.BORROWED, ObjectConf.VALIDATING, false)) {
            if (c.isRetired() || conf.get(c.wrapper) != c) {
                return;
            }
            throw new InvalidException("Object '{}' has already in this pool", c.wrapper);
        }
        onLeaveBorrowed();
        finishRecycleValidating(c, true);
    }

    void recycleLease(ObjectConf<T> c, long expectedGen) {
        if (conf.get(c.wrapper) != c) {
            return;
        }
        if (!c.casStateForGen(expectedGen, ObjectConf.BORROWED, ObjectConf.VALIDATING, false)) {
            return;
        }
        onLeaveBorrowed();
        finishRecycleValidating(c, false);
    }

    void discardLease(ObjectConf<T> c, long expectedGen) {
        if (conf.get(c.wrapper) != c) {
            return;
        }
        if (!c.casStateForGen(expectedGen, ObjectConf.BORROWED, ObjectConf.RETIRED, false)) {
            return;
        }
        onLeaveBorrowed();
        doRetire(c, RETIRE_CLOSE);
    }

    private void finishRecycleValidating(ObjectConf<T> c, boolean throwIfAlreadyInPool) {
        try {
            if (disposing || isClosed()) {
                if (c.casState(ObjectConf.VALIDATING, ObjectConf.RETIRED, false)) {
                    doRetire(c, RETIRE_CLOSE);
                }
                return;
            }
            if (size() > maxPoolSize) {
                if (c.casState(ObjectConf.VALIDATING, ObjectConf.RETIRED, false)) {
                    doRetire(c, RETIRE_OVER_CAPACITY);
                }
                return;
            }
            if (!validateHandler.test(c.wrapper.instance)) {
                if (c.casState(ObjectConf.VALIDATING, ObjectConf.RETIRED, false)) {
                    doRetire(c, RETIRE_VALIDATE);
                }
                return;
            }
            if (passivateHandler != null) {
                passivateHandler.accept(c.wrapper.instance);
            }
        } catch (Throwable e) {
            if (c.casState(ObjectConf.VALIDATING, ObjectConf.RETIRED, false)) {
                doRetire(c, RETIRE_CLOSE);
            }
            throw e;
        }

        if (disposing || isClosed()) {
            if (c.casState(ObjectConf.VALIDATING, ObjectConf.RETIRED, false)) {
                doRetire(c, RETIRE_CLOSE);
            }
            return;
        }
        if (size() > maxPoolSize) {
            if (c.casState(ObjectConf.VALIDATING, ObjectConf.RETIRED, false)) {
                doRetire(c, RETIRE_OVER_CAPACITY);
            }
            return;
        }
        if (conf.get(c.wrapper) != c) {
            return;
        }
        if (c.casState(ObjectConf.VALIDATING, ObjectConf.IDLE, true)) {
            offerSharedIdle(c);
            if (disposing || isClosed()) {
                doRetire(c, RETIRE_CLOSE);
            }
        } else if (throwIfAlreadyInPool) {
            if (c.isRetired() || conf.get(c.wrapper) != c) {
                return;
            }
            throw new InvalidException("Object '{}' has already in this pool", c.wrapper);
        }
    }

    void recordDiagnosticMetrics(long borrowsInWindow, int peakDemand, int targetSize) {
        if (!DiagnosticMetrics.isEnabled()) {
            return;
        }
        String tags = diagnosticTags();
        int total = size();
        int idle = idleSize();
        int borrowed = borrowedCount.get();
        int active = Math.max(borrowed, Math.max(0, total - idle));
        DiagnosticMetrics.record("rx.object_pool.size.count", total, tags);
        DiagnosticMetrics.record("rx.object_pool.idle.count", idle, tags);
        DiagnosticMetrics.record("rx.object_pool.active.count", active, tags);
        DiagnosticMetrics.record("rx.object_pool.borrowed.count", borrowed, tags);
        DiagnosticMetrics.record("rx.object_pool.waiting.count", waitingBorrowers.get(), tags);
        DiagnosticMetrics.record("rx.object_pool.borrow.window.count", borrowsInWindow, tags);
        DiagnosticMetrics.record("rx.object_pool.peak_demand.count", peakDemand, tags);
        DiagnosticMetrics.record("rx.object_pool.target.count", targetSize, tags);
        DiagnosticMetrics.record("rx.object_pool.target.total.count", targetSize, tags);
        DiagnosticMetrics.record("rx.object_pool.created.count", createdCount.sum(), tags);
        DiagnosticMetrics.record("rx.object_pool.retired.count", retiredCount.sum(), tags);
        DiagnosticMetrics.record("rx.object_pool.borrow.timeout.count", borrowTimeoutCount.sum(), tags);
        DiagnosticMetrics.record("rx.object_pool.maintain.rejected.count", maintainRejectedCount.sum(), tags);
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
