package org.rx.cache;

import java.lang.ref.Reference;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;

import org.rx.common.App;
import org.rx.common.Lazy;
import org.rx.common.Logger;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

import static org.rx.common.Contract.as;
import static org.rx.common.Contract.require;

/**
 * http://blog.csdn.net/nicolasyan/article/details/50840852
 */
public class WeakCache<TK, TV> {
    private static final Lazy<WeakCache<String, Object>> instance = new Lazy<>(WeakCache::new);

    public static WeakCache<String, Object> getInstance() {
        return instance.getValue();
    }

    public static Object getOrStore(Class caller, String key, Function<String, Object> supplier) {
        require(caller, key, supplier);

        String k = App.cacheKey(caller.getName() + key);
        return getInstance().getOrAdd(k, p -> supplier.apply(key));
    }

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

    public void add(TK key, TV val) {
        add(key, val, softRef);
    }

    public void add(TK key, TV val, boolean isSoftRef) {
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
        if (destroy && (ac = as(ref.get(), AutoCloseable.class)) != null) {
            try {
                ac.close();
            } catch (Exception ex) {
                Logger.error(ex, "Auto close error");
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

    public TV getOrAdd(TK key, Function<TK, TV> supplier) {
        return getOrAdd(key, supplier, softRef);
    }

    public TV getOrAdd(TK key, Function<TK, TV> supplier, boolean isSoftRef) {
        require(supplier);

        TV v;
        Reference ref = getItem(key);
        if (ref == null || (v = (TV) ref.get()) == null) {
            add(key, v = supplier.apply(key), isSoftRef);
        }
        return v;
    }
}
