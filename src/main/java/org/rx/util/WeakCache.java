package org.rx.util;

import java.lang.ref.Reference;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

import static org.rx.common.Contract.require;
import static org.rx.util.App.logError;

/**
 * Created by IntelliJ IDEA. User: za-wangxiaoming Date: 2017/8/18
 * http://blog.csdn.net/nicolasyan/article/details/50840852
 */
public final class WeakCache {
    private ConcurrentMap<String, Object> container;

    public WeakCache() {
        container = new ConcurrentHashMap<>();
    }

    private Reference getItem(String key) {
        require(key);

        return (Reference) container.get(key);
    }

    private void setItem(String key, Object val, boolean isSoftRef) {
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

    public void add(String key, Object val) {
        add(key, val, false);
    }

    public void add(String key, Object val, boolean isSoftRef) {
        setItem(key, val, isSoftRef);
    }

    public void remove(String key) {
        remove(key, true);
    }

    public void remove(String key, boolean destroy) {
        require(key);

        Reference ref = (Reference) container.remove(key);
        if (ref == null) {
            return;
        }
        if (destroy && ref.get() instanceof AutoCloseable) {
            AutoCloseable ac = (AutoCloseable) ref.get();
            try {
                ac.close();
            } catch (Exception ex) {
                logError(ex, "WeakCache.destroy");
            }
        }
        ref.clear();
    }

    public <T> T get(String key) {
        Reference ref = getItem(key);
        if (ref == null) {
            return null;
        }
        return (T) ref.get();
    }

    public <T> T getOrAdd(String key, Supplier<T> supplier) {
        T v;
        Reference ref = getItem(key);
        if (ref == null || (v = (T) ref.get()) == null) {
            add(key, v = supplier.get());
        }
        return v;
    }
}
