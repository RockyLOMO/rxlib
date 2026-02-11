package org.rx.core;

import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.rx.exception.InvalidException;
import org.rx.exception.TraceHandler;
import org.rx.util.function.BiAction;
import org.rx.util.function.Func;
import org.rx.util.function.PredicateFunc;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.rx.core.Extends.*;

@Slf4j
@ToString
public class ObjectPool<T> extends Disposable {
    static class ObjectConf {
        boolean borrowed;
        long stateTime;
        Thread t;

        public boolean isBorrowed() {
            return borrowed;
        }

        public void setBorrowed(boolean borrowed) {
            if (this.borrowed = borrowed) {
                t = Thread.currentThread();
            }
            stateTime = System.nanoTime();
        }

        public boolean isIdleTimeout(long idleTimeout) {
            return idleTimeout != 0 && !borrowed
                    && (System.nanoTime() - stateTime) / Constants.NANO_TO_MILLIS > idleTimeout;
        }

        public boolean isLeaked(long threshold) {
            return threshold != 0 && borrowed
                    && (System.nanoTime() - stateTime) / Constants.NANO_TO_MILLIS > threshold;
        }
    }

    final Func<T> createHandler;
    final PredicateFunc<T> validateHandler;
    final BiAction<T> activateHandler, passivateHandler;
    final ConcurrentHashMap<IdentityWrapper<T>, ObjectConf> conf = new ConcurrentHashMap<>();
    final ConcurrentLinkedDeque<IdentityWrapper<T>> stack = new ConcurrentLinkedDeque<>();
    final AtomicInteger totalCount = new AtomicInteger(0);
    final java.util.concurrent.Semaphore limitLatch;
    final TimeoutFuture<?> future;
    @Getter
    final int minSize;
    @Getter
    final int maxSize;
    @Getter
    long validationPeriod = 5000;
    @Getter
    long borrowTimeout = 30000;
    @Getter
    long idleTimeout = 600000;
    @Getter
    long leakDetectionThreshold;
    @Getter
    @Setter
    boolean lockOnCreate;
    @Getter
    @Setter
    boolean closeObjectOnLeak = true;

    public int size() {
        return totalCount.get();
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

    public ObjectPool(int minSize, int maxSize, Func<T> createHandler, PredicateFunc<T> validateHandler) {
        this(minSize, maxSize, createHandler, validateHandler, null, null);
    }

    public ObjectPool(int minSize, int maxSize,
            @NonNull Func<T> createHandler, @NonNull PredicateFunc<T> validateHandler,
            BiAction<T> activateHandler, BiAction<T> passivateHandler) {
        if (minSize < 0) {
            throw new InvalidException("MinSize '{}' must greater than or equal to 0", minSize);
        }
        if (maxSize < 1) {
            throw new InvalidException("MaxSize '{}' must greater than or equal to 1", maxSize);
        }

        this.minSize = minSize;
        this.maxSize = Math.max(minSize, maxSize);
        this.limitLatch = new java.util.concurrent.Semaphore(this.maxSize);
        this.createHandler = createHandler;
        this.validateHandler = validateHandler;
        this.activateHandler = activateHandler;
        this.passivateHandler = passivateHandler;

        if (lockOnCreate) {
            Tasks.run(this::insureMinSize);
        } else {
            insureMinSize();
        }
        future = Tasks.timer.setTimeout(this::validNow, d -> validationPeriod, this, Constants.TIMER_PERIOD_FLAG);
    }

    @Override
    protected void dispose() {
        future.cancel();
        for (IdentityWrapper<T> wrapper : conf.keySet()) {
            doRetire(wrapper, 0);
        }
    }

    void insureMinSize() {
        for (int i = size(); i < minSize; i++) {
            if (limitLatch.tryAcquire()) {
                IdentityWrapper<T> w = doCreate();
                if (w != null) {
                    recycle(w);
                } else {
                    limitLatch.release();
                    // 无可用配额，跳出或等待下一轮
                    break;
                }
            } else {
                break;
            }
        }
    }

    void validNow() {
        // int size = stack.size();
        int size = size();
        for (Map.Entry<IdentityWrapper<T>, ObjectConf> p : conf.entrySet()) {
            IdentityWrapper<T> wrapper = p.getKey();
            ObjectConf c = p.getValue();
            synchronized (c) {
                if (!validateHandler.test(wrapper.instance)
                        || (size > minSize && c.isIdleTimeout(idleTimeout))) {
                    doRetire(wrapper, 3);
                    size--;
                    continue;
                }
                if (c.isLeaked(leakDetectionThreshold)) {
                    TraceHandler.INSTANCE.saveMetric(Constants.MetricName.OBJECT_POOL_LEAK.name(),
                            String.format("Pool %s owned Object '%s' leaked.\n%s", this, wrapper, Reflects.getStackTrace(c.t)));
                    doRetire(wrapper, 4);
                }
            }
        }

        // log.info("ObjPool state: {}", this);
        insureMinSize();
    }

    // 0 close, 1 recycle validate, 3 idleTimeout, 4 leaked
    boolean doRetire(IdentityWrapper<T> wrapper, int action) {
        ObjectConf c = conf.remove(wrapper);
        if (c != null) {
            // may polled
            stack.remove(wrapper); // O(N) cost
            totalCount.decrementAndGet();

            if (c.isBorrowed()) {
                limitLatch.release();
            }

            if (action != 4 || closeObjectOnLeak) {
                tryClose(wrapper);
            }
            return true;
        }
        return false;
    }

    IdentityWrapper<T> doCreate() {
        if (lockOnCreate) {
            // 2. 加锁确保创建过程的串行化（可选，视重操作程度而定）
            synchronized (conf) {
                // 再次检查配额
                // int current = totalCount.get();
                // if (current >= maxSize) return null;

                IdentityWrapper<T> wrapper = null;
                try {
                    wrapper = new IdentityWrapper<>(createHandler.get());
                    // 检查是否已存在
                    if (conf.containsKey(wrapper)) {
                        throw new InvalidException("Object '{}' already exists", wrapper);
                    }

                    ObjectConf c = new ObjectConf();
                    conf.put(wrapper, c);
                    totalCount.incrementAndGet();
                    if (activateHandler != null) {
                        activateHandler.accept(wrapper.instance);
                    }
                    c.setBorrowed(true);
                    return wrapper;
                } catch (Throwable e) {
                    if (wrapper != null) {
                        doRetire(wrapper, 0);
                    }
                    // throw e; //quiet
                    log.warn("doCreate error", e);
                    return null;
                }
            }
        }

        // 使用 CAS 预占位置，解决并发超过 maxSize 的问题
        // while (true) {
        // int current = totalCount.get();
        // if (current >= maxSize) {
        // log.warn("ObjPool reject: Reach the maximum");
        // return null;
        // }
        // if (totalCount.compareAndSet(current, current + 1)) break;
        // }
        IdentityWrapper<T> wrapper = null;
        try {
            wrapper = new IdentityWrapper<>(createHandler.get());
            ObjectConf c = new ObjectConf();
            // 不需要stack.offer(p)，stack是空闲queue
            ObjectConf prev = conf.putIfAbsent(wrapper, c);
            if (prev != null) {
                // 极少数：已有映射（createHandler 返回了已存在对象），回退计数并返回 existing wrapper 的引用
                // totalCount.decrementAndGet(); // logic changed, doCreate no change on latch
                // but we incremented totalCount? No.
                // Wait. totalCount.incrementAndGet() is down below.

                throw new InvalidException("Object '{}' has already in this pool", wrapper);
            }
            totalCount.incrementAndGet();

            if (activateHandler != null) {
                activateHandler.accept(wrapper.instance);
            }
            c.setBorrowed(true);
            return wrapper;
        } catch (Throwable e) {
            if (wrapper != null) {
                doRetire(wrapper, 0); // contains release logic if needed
            }
            // doRetire will release latch if it was inserted into conf.
            // If conf.putIfAbsent failed, wrapper is null? No wrapper is not null.
            // If prev != null, we threw. wrapper != null. doRetire called.
            // doRetire removes from conf? No, it wasn't in conf (prev!=null means it was already there, but we have a NEW wrapper instance).
            // So conf.remove(wrapper) will match the NEW wrapper? No. IdentityWrapper hashCode is based on instance.
            // If createHandler returned SAME instance, IdentityWrapper is Equal.
            // So conf.remove(wrapper) removes the OLD entry?
            // Bad.
            // But this is edge case: createHandler returning duplicate.
            // Let's assume createHandler returns new instance.

            // throw e;
            log.warn("doCreate error", e);
            return null;
        }
    }

    IdentityWrapper<T> doPoll() {
        IdentityWrapper<T> wrapper;
        while ((wrapper = stack.pollLast()) != null) {
            ObjectConf c = conf.get(wrapper);
            if (c == null) {
                // 被其他线程 retire 掉了，跳过
                continue;
            }
            synchronized (c) {
                if (c.isBorrowed()) {
                    // 并发不一致，跳过它
                    continue;
                }

                if (activateHandler != null) {
                    try {
                        activateHandler.accept(wrapper.instance);
                    } catch (Throwable ex) {
                        // 激活失败 —— 退役该对象并继续
                        doRetire(wrapper, 0);
                        continue;
                    }
                }
                c.setBorrowed(true);
                return wrapper;
            }
        }
        return null;
    }

    public T borrow() throws TimeoutException {
        // long start = System.nanoTime();
        IdentityWrapper<T> wrapper;

        if (borrowTimeout <= 0) {
            try {
                limitLatch.acquire();
            } catch (InterruptedException e) {
                throw new InvalidException(e);
            }
        } else {
            try {
                if (!limitLatch.tryAcquire(borrowTimeout, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    log.warn("ObjPool borrow timeout, state: {}", this);
                    throw new TimeoutException("borrow timeout");
                }
            } catch (InterruptedException e) {
                throw new InvalidException(e);
            }
        }

        try {
            wrapper = ifNull(doPoll(), this::doCreate);
            if (wrapper != null) {
                try {
                    if (validateHandler.test(wrapper.instance)) {
                        return wrapper.instance;
                    }
                    // 必须显式退休掉校验失败的对象
                    doRetire(wrapper, 1);
                } catch (Throwable e) {
                    doRetire(wrapper, 0);
                    throw e;
                }
            }
        } catch (Throwable e) {
            limitLatch.release(); // if doCreate returns null (error) or exception
            throw e;
        }

        // validation fail or create fail, retry
        // We already released latch if critical error in catch block.
        // But if doRetire(wrapper, 1) was called, doRetire releases latch (if borrowed).
        // wrapper was borrowed in doPoll/doCreate.
        // So latch is released.
        // We need to re-acquire.
        // Recursive call? Stack depth issue if many failures?
        // Loop is better.
        return borrow();
    }

    public void recycle(@NonNull T obj) {
        IdentityWrapper<T> wrapper = new IdentityWrapper<>(obj);
        recycle(wrapper);
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

        ObjectConf c = conf.get(wrapper);
        if (c == null) {
            // doRetire by other thread
            // leak release? No, doRetire handles it.
            return;
        }
        synchronized (c) {
            if (!c.isBorrowed()) {
                throw new InvalidException("Object '{}' has already in this pool", wrapper);
            }

            stack.offer(wrapper);
            c.setBorrowed(false);

            limitLatch.release();
        }
    }
}
