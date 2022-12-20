package org.rx.core;

import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.rx.exception.InvalidException;
import org.rx.exception.TraceHandler;
import org.rx.util.function.BiAction;
import org.rx.util.function.Func;
import org.rx.util.function.PredicateFunc;

import java.util.Deque;
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
    final Deque<T> stack = new ConcurrentLinkedDeque<>();
    final Map<T, ObjectConf> conf = new ConcurrentHashMap<>();
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
    long validationTimeout = 5000;
    //    long keepaliveTime;
//    long maxLifetime = 1800000;
    @Getter
    long leakDetectionThreshold;
//    @Getter
//    @Setter
//    boolean retireLeak;

    public int size() {
        return size.get();
    }

    public void setBorrowTimeout(long borrowTimeout) {
        this.borrowTimeout = Math.max(250, borrowTimeout);
    }

    public void setIdleTimeout(long idleTimeout) {
        this.idleTimeout = idleTimeout == 0 ? 0 : Math.max(10000, idleTimeout);
    }

    public void setValidationTimeout(long validationTimeout) {
        this.validationTimeout = Math.max(250, validationTimeout);
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

        for (int i = 0; i < minSize; i++) {
            doCreate();
        }
        Tasks.timer.setTimeout(this::validNow, d -> validationTimeout, this, TimeoutFlag.PERIOD.flags());
    }

    @Override
    protected void freeObjects() {
        for (T obj : stack) {
            doRetire(obj);
        }
    }

    void validNow() {
        eachQuietly(Linq.from(conf.entrySet()).orderBy(p -> p.getValue().stateTime), p -> {
            T obj = p.getKey();
            ObjectConf c = p.getValue();
            if (!validateHandler.test(obj)
                    || (size() > minSize && c.isIdleTimeout(idleTimeout))) {
                doRetire(obj);
                return;
            }
            if (c.isLeaked(leakDetectionThreshold)) {
                TraceHandler.INSTANCE.saveMetric(Constants.MetricName.OBJECT_POOL_LEAK.name(),
                        String.format("Object '%s' leaked.\n%s", obj, Reflects.getStackTrace(c.t)));
//                if (retireLeak) {
                    doRetire(obj);
//                }
            }
        });
    }

    T doCreate() {
        if (size() > maxSize) {
            return null;
        }

        T obj = createHandler.get();

        if (!stack.offer(obj)) {
//            throw new InvalidException("create object fail");
            return null;
        }
        ObjectConf c = new ObjectConf();
        c.setBorrowed(true);
        if (conf.putIfAbsent(obj, c) != null) {
            throw new InvalidException("create object fail, object '{}' has already in this pool", obj);
        }
        size.incrementAndGet();

        if (passivateHandler != null) {
            passivateHandler.accept(obj);
        }
        return obj;
    }

    boolean doRetire(T obj) {
        boolean ok;

        ok = stack.remove(obj);
        if (ok) {
            ObjectConf c = conf.remove(obj);
            size.decrementAndGet();

            if (!c.isBorrowed()) {
                tryClose(obj);
            }
        }
        log.info("doRetire {} -> {}", obj, ok);
        return ok;
    }

    T doPoll() {
        T obj;
        ObjectConf c;
        while ((obj = stack.poll()) != null && (c = conf.get(obj)) != null && !c.isBorrowed()) {
            c.setBorrowed(true);
            return obj;
        }
        return null;
    }

    public T borrow() throws TimeoutException {
        long start = System.nanoTime();
        T obj;

        while ((obj = ifNull(doPoll(), this::doCreate)) == null || !validateHandler.test(obj)) {
            long bt = (System.nanoTime() - start) / Constants.NANO_TO_MILLIS;
            if (borrowTimeout > Constants.TIMEOUT_INFINITE && bt > borrowTimeout) {
                log.warn("borrow timeout, pool state={}", this);
                throw new TimeoutException("borrow timeout");
            }
            sleep(bt);
        }
        return obj;
    }

    public void recycle(@NonNull T obj) {
        if (!validateHandler.test(obj)) {
            doRetire(obj);
            return;
        }

        ObjectConf c = conf.get(obj);
        if (c == null) {
            throw new InvalidException("Object '{}' not belong to this pool", obj);
        }
        if (!c.isBorrowed()) {
            throw new InvalidException("Object '{}' has already in this pool", obj);
        }
        c.setBorrowed(false);
        if (
//                size() > maxSize ||  //Not required
                !stack.offer(obj)) {
            doRetire(obj);
            return;
        }

        if (passivateHandler != null) {
            passivateHandler.accept(obj);
        }
    }

    public void retire(@NonNull T obj) {
        if (!doRetire(obj)) {
            throw new InvalidException("Object '{}' not belong to this pool", obj);
        }
    }

    @Override
    public String toString() {
        return "ObjectPool{" +
                "stack=" + stack.size() +
                ", conf=" + conf.size() +
                ", size=" + size +
                ", borrowTimeout=" + borrowTimeout +
                ", idleTimeout=" + idleTimeout +
                ", validationTimeout=" + validationTimeout +
                ", leakDetectionThreshold=" + leakDetectionThreshold +
                '}';
    }
}
