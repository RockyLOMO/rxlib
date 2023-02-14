package org.rx.core;

import lombok.Getter;
import lombok.NonNull;
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
public class ObjectPool<T> extends Disposable {
    static class ObjectConf {
        //        final long lifetime = System.nanoTime();
        boolean borrowed;
        long stateTime;
        Thread t;

        public synchronized boolean isBorrowed() {
            return borrowed;
        }

        public synchronized void setBorrowed(boolean borrowed) {
            if (this.borrowed = borrowed) {
                t = Thread.currentThread();
            }
            stateTime = System.nanoTime();
        }

        public synchronized boolean isIdleTimeout(long idleTimeout) {
            return idleTimeout != 0 && !borrowed
                    && (System.nanoTime() - stateTime) / Constants.NANO_TO_MILLIS > idleTimeout;
        }

        public synchronized boolean isLeaked(long threshold) {
            return threshold != 0 && borrowed
                    && (System.nanoTime() - stateTime) / Constants.NANO_TO_MILLIS > threshold;
        }
    }

    final Func<T> createHandler;
    final PredicateFunc<T> validateHandler;
    final BiAction<T> passivateHandler;
    final ConcurrentLinkedDeque<IdentityWrapper<T>> stack = new ConcurrentLinkedDeque<>();
    final Map<IdentityWrapper<T>, ObjectConf> conf = new ConcurrentHashMap<>();
    final AtomicInteger size = new AtomicInteger();
    @Getter
    final int minSize;
    @Getter
    final int maxSize;
    @Getter
    long borrowTimeout = 30000;
    @Getter
    long idleTimeout = 600000;
    @Getter
    long validationTime = 5000;
    //    long keepaliveTime;
//    long maxLifetime = 1800000;
    @Getter
    long leakDetectionThreshold;

    public int size() {
        return size.get();
    }

    public void setBorrowTimeout(long borrowTimeout) {
        this.borrowTimeout = Math.max(250, borrowTimeout);
    }

    public void setIdleTimeout(long idleTimeout) {
        this.idleTimeout = idleTimeout == 0 ? 0 : Math.max(10000, idleTimeout);
    }

    public void setValidationTime(long validationTime) {
        this.validationTime = Math.max(250, validationTime);
    }

    public void setLeakDetectionThreshold(long leakDetectionThreshold) {
        this.leakDetectionThreshold = Math.max(2000, leakDetectionThreshold);
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
        Tasks.timer.setTimeout(this::validNow, d -> validationTime, this, TimeoutFlag.PERIOD.flags());
    }

    @Override
    protected void freeObjects() {
        for (IdentityWrapper<T> wrapper : stack) {
            doRetire(wrapper, 0);
        }
    }

    void insureMinSize() {
        for (int i = size(); i < minSize; i++) {
            quietly(this::doCreate);
        }
    }

    void validNow() {
        eachQuietly(Linq.from(conf.entrySet()).orderBy(p -> p.getValue().stateTime), p -> {
            IdentityWrapper<T> wrapper = p.getKey();
            ObjectConf c = p.getValue();
            if (!validateHandler.test(wrapper.instance)
                    || (size() > minSize && c.isIdleTimeout(idleTimeout))) {
                doRetire(wrapper, 3);
                return;
            }
            if (c.isLeaked(leakDetectionThreshold)) {
                TraceHandler.INSTANCE.saveMetric(Constants.MetricName.OBJECT_POOL_LEAK.name(),
                        String.format("Pool %s owned Object '%s' leaked.\n%s", this, wrapper, Reflects.getStackTrace(c.t)));
                doRetire(wrapper, 4);
            }
        });

        log.info("ObjPool state: {}", this);
        insureMinSize();
    }

    IdentityWrapper<T> doCreate() {
        if (size() > maxSize) {
            log.warn("ObjPool reject: Reach the maximum");
            return null;
        }

        IdentityWrapper<T> wrapper = new IdentityWrapper<>(createHandler.get());
        if (!stack.offer(wrapper)) {
            log.error("ObjPool create object fail: Offer stack fail");
            return null;
        }
        ObjectConf c = new ObjectConf();
        c.setBorrowed(true);
        if (conf.putIfAbsent(wrapper, c) != null) {
            throw new InvalidException("create object fail, object '{}' has already in this pool", wrapper);
        }
        size.incrementAndGet();

        if (passivateHandler != null) {
            passivateHandler.accept(wrapper.instance);
        }
        return wrapper;
    }

    //0 close, 1 recycle validate, 2 recycle offer, 3 idleTimeout, 4 leaked
    boolean doRetire(IdentityWrapper<T> wrapper, int action) {
        boolean ok;

        ok = stack.remove(wrapper);
//        if (ok) {
        ObjectConf c = conf.remove(wrapper);
        if (c != null) {
            size.decrementAndGet();

            if (!c.isBorrowed()) {
                tryClose(wrapper);
            }
        }
//        }
        log.info("ObjPool doRetire[{}] {} -> {}", action, wrapper, ok);
        return ok;
    }

    IdentityWrapper<T> doPoll() {
        IdentityWrapper<T> wrapper;
        ObjectConf c;
        while ((wrapper = stack.poll()) != null && (c = conf.get(wrapper)) != null && !c.isBorrowed()) {
            c.setBorrowed(true);
            return wrapper;
        }
        return null;
    }

    public T borrow() throws TimeoutException {
        long start = System.nanoTime();
        IdentityWrapper<T> wrapper;

        while ((wrapper = ifNull(doPoll(), this::doCreate)) == null || !validateHandler.test(wrapper.instance)) {
            long bt = (System.nanoTime() - start) / Constants.NANO_TO_MILLIS;
            if (borrowTimeout > Constants.TIMEOUT_INFINITE && bt > borrowTimeout) {
                log.warn("ObjPool borrow timeout, state: {}", this);
                throw new TimeoutException("borrow timeout");
            }
            sleep(bt);
        }
        return wrapper.instance;
    }

    public void recycle(@NonNull T obj) {
        IdentityWrapper<T> wrapper = new IdentityWrapper<>(obj);
        if (!validateHandler.test(wrapper.instance)) {
            doRetire(wrapper, 1);
            return;
        }

        ObjectConf c = conf.get(wrapper);
        if (c == null) {
            throw new InvalidException("Object '{}' not belong to this pool", wrapper);
        }
        if (!c.isBorrowed()) {
            throw new InvalidException("Object '{}' has already in this pool", wrapper);
        }
        c.setBorrowed(false);
        if (
//                size() > maxSize ||  //Not required
                !stack.offer(wrapper)) {
            doRetire(wrapper, 2);
            return;
        }

        if (passivateHandler != null) {
            passivateHandler.accept(wrapper.instance);
        }
    }

    public void retire(@NonNull T obj) {
        IdentityWrapper<T> wrapper = new IdentityWrapper<>(obj);
        if (!doRetire(wrapper, 10)) {
            throw new InvalidException("Object '{}' not belong to this pool", wrapper);
        }
    }

    @Override
    public String toString() {
        return "ObjectPool{" +
                "stack=" + stack.size() + ":" + conf.size() +
                ", size=" + size +
                ", poolSize=" + minSize + "-" + maxSize +
                ", borrowTimeout=" + borrowTimeout +
                ", idleTimeout=" + idleTimeout +
                ", validationTime=" + validationTime +
                ", leakDetectionThreshold=" + leakDetectionThreshold +
                '}';
    }
}
