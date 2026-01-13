package org.rx.core;

import lombok.Getter;
import lombok.NonNull;
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
    final BiAction<T> passivateHandler;
    final ConcurrentHashMap<IdentityWrapper<T>, ObjectConf> conf = new ConcurrentHashMap<>();
    final ConcurrentLinkedDeque<IdentityWrapper<T>> stack = new ConcurrentLinkedDeque<>();
    final AtomicInteger totalCount = new AtomicInteger(0);
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

    public ObjectPool(int minSize, Func<T> createHandler, PredicateFunc<T> validateHandler) {
        this(minSize, minSize, createHandler, validateHandler, null);
    }

    public ObjectPool(int minSize, int maxSize,
                      @NonNull Func<T> createHandler, @NonNull PredicateFunc<T> validateHandler,
                      BiAction<T> passivateHandler) {
        if (minSize < 0) {
            throw new InvalidException("MinSize '{}' must greater than or equal to 0", minSize);
        }
        if (maxSize < 1) {
            throw new InvalidException("MaxSize '{}' must greater than or equal to 1", maxSize);
        }

        this.minSize = minSize;
        this.maxSize = Math.max(minSize, maxSize);
        this.createHandler = createHandler;
        this.validateHandler = validateHandler;
        this.passivateHandler = passivateHandler;

        insureMinSize();
        Tasks.timer.setTimeout(this::validNow, d -> validationPeriod, this, Constants.TIMER_PERIOD_FLAG);
    }

    @Override
    protected void dispose() {
        for (IdentityWrapper<T> wrapper : conf.keySet()) {
            doRetire(wrapper, 0);
        }
    }

    void insureMinSize() {
        for (int i = size(); i < minSize; i++) {
            doCreate();
        }
    }

    void validNow() {
        for (Map.Entry<IdentityWrapper<T>, ObjectConf> p : conf.entrySet()) {
            IdentityWrapper<T> wrapper = p.getKey();
            ObjectConf c = p.getValue();
            synchronized (c) {
                if (!validateHandler.test(wrapper.instance)
                        || (size() > minSize && c.isIdleTimeout(idleTimeout))) {
                    doRetire(wrapper, 3);
                    continue;
                }
                if (c.isLeaked(leakDetectionThreshold)) {
                    TraceHandler.INSTANCE.saveMetric(Constants.MetricName.OBJECT_POOL_LEAK.name(),
                            String.format("Pool %s owned Object '%s' leaked.\n%s", this, wrapper, Reflects.getStackTrace(c.t)));
                    doRetire(wrapper, 4);
                }
            }
        }

//        log.info("ObjPool state: {}", this);
        insureMinSize();
    }

    IdentityWrapper<T> doCreate() {
        // 使用 CAS 预占位置，解决并发超过 maxSize 的问题
        while (true) {
            int current = totalCount.get();
            if (current >= maxSize) {
                log.warn("ObjPool reject: Reach the maximum");
                return null;
            }
            if (totalCount.compareAndSet(current, current + 1)) break;
        }

        try {
            IdentityWrapper<T> wrapper = new IdentityWrapper<>(createHandler.get());
            conf.computeIfAbsent(wrapper, p -> {
                //不需要，stack是空闲
//            if (!stack.offer(p)) {
//                throw new InvalidException("Create object fail, object '{}' fail to offer", p);
//            }
                ObjectConf c = new ObjectConf();
                c.setBorrowed(true);
                if (passivateHandler != null) {
                    passivateHandler.accept(p.instance);
                }
                return c;
            });
            return wrapper;
        } catch (Throwable e) {
            totalCount.decrementAndGet();
            throw e;
        }
    }

    //0 close, 1 recycle validate, 3 idleTimeout, 4 leaked
    boolean doRetire(IdentityWrapper<T> wrapper, int action) {
        boolean forceClose = action == 0 || action == 1;
        ObjectConf c = conf.remove(wrapper);
        if (c != null) {
            //may polled
            stack.remove(wrapper);
            totalCount.decrementAndGet();

            if (forceClose || !c.isBorrowed()) {
                tryClose(wrapper);
            }
            return true;
        }
        if (forceClose) {
            tryClose(wrapper);
        }
        return false;
    }

    IdentityWrapper<T> doPoll() {
        IdentityWrapper<T> wrapper;
        ObjectConf c;
        //c == null is doRetire by other thread
        while ((wrapper = stack.poll()) != null && (c = conf.get(wrapper)) != null) {
            synchronized (c) {
                if (c.isBorrowed()) {
                    throw new InvalidException("Poll object fail, object '{}' fail to poll", wrapper);
                }

                c.setBorrowed(true);
                if (passivateHandler != null) {
                    passivateHandler.accept(wrapper.instance);
                }
                return wrapper;
            }
        }
        return null;
    }

    public T borrow() throws TimeoutException {
        long start = System.nanoTime();
        IdentityWrapper<T> wrapper;

        while (true) {
            wrapper = ifNull(doPoll(), this::doCreate);
            //wrapper == null 达到上限
            if (wrapper != null) {
                if (validateHandler.test(wrapper.instance)) {
                    return wrapper.instance;
                }
                // 必须显式退休掉校验失败的对象
                doRetire(wrapper, 1);
            }

            long bt = (System.nanoTime() - start) / Constants.NANO_TO_MILLIS;
            if (borrowTimeout > Constants.TIMEOUT_INFINITE && bt > borrowTimeout) {
                log.warn("ObjPool borrow timeout, state: {}", this);
                throw new TimeoutException("borrow timeout");
            }
            sleep(Math.min(Math.max(bt, 10), 50)); // 至少睡 10ms
        }
    }

    public void recycle(@NonNull T obj) {
        IdentityWrapper<T> wrapper = new IdentityWrapper<>(obj);
        if (!validateHandler.test(wrapper.instance)) {
            doRetire(wrapper, 1);
            return;
        }
        ObjectConf c = conf.get(wrapper);
        if (c == null) {
//                throw new InvalidException("Object '{}' not belong to this pool", wrapper);
            //doRetire by other thread
            return;
        }
        synchronized (c) {
            if (!c.isBorrowed()) {
                throw new InvalidException("Object '{}' has already in this pool", wrapper);
            }

            stack.offer(wrapper);
            c.setBorrowed(false);
        }
    }

    public void retire(@NonNull T obj) {
        IdentityWrapper<T> wrapper = new IdentityWrapper<>(obj);
        if (!doRetire(wrapper, 10)) {
            throw new InvalidException("Object '{}' not belong to this pool", wrapper);
        }
    }
}
