package org.rx.core;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.rx.beans.Tuple;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

import static org.rx.core.Contract.as;
import static org.rx.core.Contract.require;

/**
 * http://blog.csdn.net/nicolasyan/article/details/50840852
 */
@Slf4j
public class WeakCache<TK, TV> {
    public static final WeakCache<String, Object> instance = new WeakCache<>();

    private ReferenceQueue<Tuple<TK, TV>> queue;
    private ConcurrentMap<TK, Reference<Tuple<TK, TV>>> container;
    @Getter
    @Setter
    private boolean softRef;

    public WeakCache() {
        queue = new ReferenceQueue<>();
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
        Tuple<TK, TV> kv = Tuple.of(key, val);
        container.put(key, isSoftRef ? new SoftReference<>(kv, queue) : new WeakReference<>(kv, queue));
    }

    public void remove(TK key) {
        remove(key, true);
    }

    public void remove(TK key, boolean destroy) {
        require(key);

        Reference<Tuple<TK, TV>> ref = container.remove(key);
        if (ref == null) {
            return;
        }
        Tuple<TK, TV> kv = ref.get();
        if (kv == null) {
            return;
        }

        AutoCloseable ac;
        if (destroy && (ac = as(kv.right, AutoCloseable.class)) != null) {
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

        Reference<Tuple<TK, TV>> ref;
        while ((ref = (Reference<Tuple<TK, TV>>) queue.poll()) != null) {
            Tuple<TK, TV> kv = ref.get();
            if (kv == null) {
                log.warn("queue ref is null");
                continue;
            }
            container.remove(kv.left);
        }

        ref = container.get(key);
        if (ref == null) {
            return null;
        }
        Tuple<TK, TV> kv = ref.get();
        if (kv == null) {
            return null;
        }
        return kv.right;
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
