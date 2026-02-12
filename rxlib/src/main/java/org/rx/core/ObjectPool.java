package org.rx.core;

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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.rx.core.Extends.tryClose;

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
    final AtomicInteger totalCount = new AtomicInteger();
    final ConcurrentBlockingDeque<IdentityWrapper<T>> stack;
    final TimeoutFuture<?> future;
    @Getter
    final int minSize;
    @Getter
    final int maxSize;
    @Getter
    long validationPeriod = 5000;
    @Getter
    long borrowTimeout = 10000;
    @Getter
    long idleTimeout = 600000;
    @Getter
    long leakDetectionThreshold;
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
        stack = new ConcurrentBlockingDeque<>(this.maxSize);
        this.createHandler = createHandler;
        this.validateHandler = validateHandler;
        this.activateHandler = activateHandler;
        this.passivateHandler = passivateHandler;

        Tasks.run(this::insureMinSize);
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
        while (size() < minSize) {
            IdentityWrapper<T> w = doCreate();
            if (w != null) {
                recycle(w);
            } else {
                // 无可用配额，跳出或等待下一轮
                break;
            }
        }
    }

    void validNow() {
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
            if (!c.isBorrowed()) {
                stack.removeLastOccurrence(wrapper); // O(N) cost
            }
            totalCount.decrementAndGet();

            if (action != 4 || closeObjectOnLeak) {
                tryClose(wrapper);
            }
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
            ObjectConf c = new ObjectConf();
            // 不需要stack.offer(p)，stack是空闲queue
            ObjectConf prev = conf.putIfAbsent(wrapper, c);
            if (prev != null) {
                // 极少数：已有映射（createHandler 返回了已存在对象）
                totalCount.decrementAndGet();
                throw new InvalidException("Object '{}' has already in this pool", wrapper);
            }
            if (activateHandler != null) {
                activateHandler.accept(wrapper.instance);
            }
            c.setBorrowed(true);
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
        IdentityWrapper<T> wrapper;
        if ((wrapper = stack.pollLast(timeout, TimeUnit.MILLISECONDS)) != null) {
            ObjectConf c = conf.get(wrapper);
            if (c == null) {
                // 被其他线程 retire 掉了，跳过
                return null;
            }
            synchronized (c) {
                if (c.isBorrowed()) {
                    // 并发不一致，跳过它
                    return null;
                }
                if (activateHandler != null) {
                    try {
                        activateHandler.accept(wrapper.instance);
                    } catch (Throwable e) {
                        // 激活失败 —— 退役该对象并继续
                        doRetire(wrapper, 0);
                        log.warn("doPoll error", e);
                        return null;
                    }
                }
                c.setBorrowed(true);
                return wrapper;
            }
        }
        return null;
    }

    public T borrow() throws TimeoutException {
        Throwable lastError = null;
        long beginNanos = System.nanoTime();
        long remainingTime = 1;
        IdentityWrapper<T> wrapper = null;
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
                        return wrapper.instance;
                    }
                    // 必须显式退休掉校验失败的对象
                    doRetire(wrapper, 1);
                }
            } catch (Throwable e) {
                if (wrapper != null) {
                    doRetire(wrapper, 0);
                }
                lastError = e;
            }
            long elapsedMs = (System.nanoTime() - beginNanos) / Constants.NANO_TO_MILLIS;
            remainingTime = borrowTimeout - elapsedMs;
        }
        String msg = "borrow timeout";
        if (lastError != null) {
            msg += ": " + lastError.getMessage();
        }
        throw new TimeoutException(msg);
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
            return;
        }
        synchronized (c) {
            if (!c.isBorrowed()) {
                throw new InvalidException("Object '{}' has already in this pool", wrapper);
            }
            if (!stack.offer(wrapper)) {
                doRetire(wrapper, 0); // Fixed: ensure totalCount decremented
                return;
            }
            c.setBorrowed(false);
        }
    }
}
