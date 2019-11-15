package org.rx.core;

import java.lang.ref.Reference;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;

import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.util.function.BiFunc;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.rx.core.Contract.*;

/**
 * ReferenceQueue 不准
 * http://blog.csdn.net/nicolasyan/article/details/50840852
 */
@Slf4j
public class WeakCache<TK, TV> {
    @Getter
    private static final WeakCache<String, Object> instance = new WeakCache<>();

    public static <T> T getOrStore(String key, BiFunc<String, Object> supplier) {
        return getOrStore(key, supplier, false);
    }

    public static <T> T getOrStore(String key, BiFunc<String, Object> supplier, boolean isSoftRef) {
        return (T) instance.getOrAdd(key, supplier, isSoftRef);
    }

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
        log.debug("add key {} softRef={}", key, softRef);
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

        if (destroy) {
            tryClose(val);
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

    public TV getOrAdd(TK key, BiFunc<TK, TV> supplier) {
        return getOrAdd(key, supplier, softRef);
    }

    @SneakyThrows
    public TV getOrAdd(TK key, BiFunc<TK, TV> supplier, boolean isSoftRef) {
        require(supplier);

        TV v = get(key);
        if (v == null) {
            add(key, v = supplier.invoke(key), isSoftRef);
        }
        return v;
    }
}
