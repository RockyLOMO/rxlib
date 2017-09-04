package org.rx.util;

import java.lang.ref.Reference;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

import static org.rx.common.Contract.require;
import static org.rx.util.App.As;
import static org.rx.util.App.logError;

/**
 * http://blog.csdn.net/nicolasyan/article/details/50840852
 */
public final class WeakCache<TK, TV> {
    private ConcurrentMap<TK, Reference> container;
    private boolean                      softRef;

    public boolean isSoftRef() {
        return softRef;
    }

    public void setSoftRef(boolean softRef) {
        this.softRef = softRef;
    }

    public WeakCache() {
        container = new ConcurrentHashMap<>();
    }

    private Reference getItem(TK key) {
        require(key);

        return container.get(key);
    }

    private void setItem(TK key, TV val, boolean isSoftRef) {
        require(key, val);

        Reference ref = getItem(key);
        if (ref != null && isSoftRef ? ref instanceof SoftReference : ref instanceof WeakReference) {
            Object v = ref.get();
            if (v != null && v.equals(val)) {
                return;
            }
        }
        container.put(key, isSoftRef ? new SoftReference(val) : new WeakReference(val));
    }

    public void add(TK key, TV val) {
        add(key, val, softRef);
    }

    public void add(TK key, TV val, boolean isSoftRef) {
        setItem(key, val, isSoftRef);
    }

    public void remove(TK key) {
        remove(key, true);
    }

    public void remove(TK key, boolean destroy) {
        require(key);

        Reference ref = container.remove(key);
        if (ref == null) {
            return;
        }
        AutoCloseable ac;
        if (destroy && (ac = As(ref.get(), AutoCloseable.class)) != null) {
            try {
                ac.close();
            } catch (Exception ex) {
                logError(ex, "WeakCache.destroy");
            }
        }
        ref.clear();
    }

    public TV get(TK key) {
        Reference ref = getItem(key);
        if (ref == null) {
            return null;
        }
        return (TV) ref.get();
    }

    public TV getOrAdd(TK key, Supplier<TV> supplier) {
        return getOrAdd(key, supplier, softRef);
    }

    public TV getOrAdd(TK key, Supplier<TV> supplier, boolean isSoftRef) {
        TV v;
        Reference ref = getItem(key);
        if (ref == null || (v = (TV) ref.get()) == null) {
            add(key, v = supplier.get(), isSoftRef);
        }
        return v;
    }
}
