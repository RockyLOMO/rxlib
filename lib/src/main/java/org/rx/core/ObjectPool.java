package org.rx.core;

import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.rx.exception.InvalidException;
import org.rx.util.function.BiAction;
import org.rx.util.function.Func;

import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.rx.core.Extends.*;

@Slf4j
public class ObjectPool<T> extends Disposable {
    static class ObjectConf {
        //        final long lifetime = System.nanoTime();
        long idleTime;

        public synchronized boolean isBorrowed() {
            return idleTime == Constants.TIMEOUT_INFINITE;
        }

        public synchronized void setBorrowed(boolean borrowed) {
            idleTime = borrowed ? Constants.TIMEOUT_INFINITE : System.nanoTime();
        }

        public synchronized boolean isIdleTimeout(long idleTimeout) {
            return idleTime != Constants.TIMEOUT_INFINITE
                    && (System.nanoTime() - idleTime) / Constants.NANO_TO_MILLIS > idleTimeout;
        }
    }

    final Func<T> createHandler;
    final Predicate<T> validateHandler;
    final BiAction<T> passivateHandler;
    final Deque<T> stack = new ConcurrentLinkedDeque<>();
    final Map<T, ObjectConf> conf = new ConcurrentHashMap<>();
    final AtomicInteger size = new AtomicInteger();
    @Getter
    final int minSize;
    @Getter
    final int maxSize;
    @Getter
    @Setter
    long borrowTimeout = Constants.TIMEOUT_INFINITE;
    @Getter
    @Setter
    long idleTimeout = 600000;
//    long keepaliveTime;
//    long maxLifetime = 1800000;

    public int size() {
        return size.get();
    }

    public ObjectPool(int minSize, Func<T> createHandler, Predicate<T> validateHandler) {
        this(minSize, minSize, createHandler, validateHandler, null);
    }

    public ObjectPool(int minSize, int maxSize,
                      @NonNull Func<T> createHandler, @NonNull Predicate<T> validateHandler,
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
        Tasks.schedulePeriod(() -> eachQuietly(conf.entrySet(), p -> {
            ObjectConf c = p.getValue();
            if (!validateHandler.test(p.getKey()) || c.isIdleTimeout(idleTimeout)) {
                doRetire(p.getKey());
            }
        }), 30000);
    }

    @Override
    protected void freeObjects() {
        for (T obj : stack) {
            doRetire(obj);
        }
    }

    @SneakyThrows
    T doCreate() {
        if (size() > maxSize) {
            return null;
        }

        T obj = createHandler.invoke();

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
            passivateHandler.invoke(obj);
        }
        return obj;
    }

    boolean doRetire(T obj) {
        boolean ok;

        ok = stack.remove(obj);
        conf.remove(obj);
        size.decrementAndGet();

        log.debug("doRetire {} -> {}", obj, ok);
        tryClose(obj);
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
            if (borrowTimeout > Constants.TIMEOUT_INFINITE
                    && (System.nanoTime() - start) / Constants.NANO_TO_MILLIS > borrowTimeout) {
                throw new TimeoutException("borrow timeout");
            }
        }
        return obj;
    }

    @SneakyThrows
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
//                size() > maxSize ||  //不需要
                !stack.offer(obj)) {
            doRetire(obj);
            return;
        }

        if (passivateHandler != null) {
            passivateHandler.invoke(obj);
        }
    }

    public void retire(@NonNull T obj) {
        if (!doRetire(obj)) {
            throw new InvalidException("Object '{}' not belong to this pool", obj);
        }
    }
}
