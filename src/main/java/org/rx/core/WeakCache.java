package org.rx.core;

import java.lang.ref.Reference;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

import static org.rx.core.Contract.as;
import static org.rx.core.Contract.require;

/**
 * ReferenceQueue 不准
 * http://blog.csdn.net/nicolasyan/article/details/50840852
 */
@Slf4j
public class WeakCache<TK, TV> {
    public static final WeakCache<String, Object> instance = new WeakCache<>();

    private ConcurrentMap<TK, Reference<TV>> container;
    @Getter
    @Setter
    private boolean softRef;

    public int size() {
        return container.size();
    }

    public WeakCache() {
        container = new ConcurrentHashMap<>();
    }

    public void add(TK key, TV val) {
        add(key, val, softRef);
    }

    public void add(TK key, TV val, boolean isSoftRef) {
        require(key, val);

        TV v = get(key);
        if (v != null && v.equals(val)) {
            return;
        }
        container.put(key, isSoftRef ? new SoftReference<>(val) : new WeakReference<>(val));
    }

    public void remove(TK key) {
        remove(key, true);
    }

    public void remove(TK key, boolean destroy) {
        require(key);

        Reference<TV> ref = container.remove(key);
        if (ref == null) {
            return;
        }
        TV val = ref.get();
        if (val == null) {
            return;
        }

        AutoCloseable ac;
        if (destroy && (ac = as(val, AutoCloseable.class)) != null) {
            try {
                ac.close();
            } catch (Exception ex) {
                log.error("Auto close error", ex);
            }
        }
        ref.clear();
    }

    public TV get(TK key) {
        require(key);

        Reference<TV> ref = container.get(key);
        if (ref == null) {
            log.debug("get key {} is null", key);
            return null;
        }
        TV val = ref.get();
        if (val == null) {
            log.debug("get key {} is gc", key);
            remove(key);
            return null;
        }
        return val;
    }

    public TV getOrAdd(TK key, Function<TK, TV> supplier) {
        return getOrAdd(key, supplier, softRef);
    }

    public TV getOrAdd(TK key, Function<TK, TV> supplier, boolean isSoftRef) {
        require(supplier);

        TV v = get(key);
        if (v == null) {
            add(key, v = supplier.apply(key), isSoftRef);
        }
        return v;
    }
}
