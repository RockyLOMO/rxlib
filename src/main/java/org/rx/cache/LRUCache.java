package org.rx.cache;

import org.rx.common.*;
import org.rx.beans.DateTime;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.rx.common.Contract.as;
import static org.rx.common.Contract.require;
import static org.rx.util.AsyncTask.TaskFactory;

public final class LRUCache<TK, TV> extends Disposable {
    private class CacheItem {
        public TV            value;
        private int          expireSeconds;
        private DateTime     createTime;
        private Consumer<TV> expireCallback;

        public CacheItem(TV value, int expireSeconds, Consumer<TV> expireCallback) {
            this.value = value;
            this.expireSeconds = expireSeconds;
            this.expireCallback = expireCallback;
            refresh();
        }

        public void refresh() {
            if (expireSeconds > -1) {
                createTime = DateTime.utcNow();
            }
        }

        public void callback() {
            if (expireCallback != null) {
                expireCallback.accept(value);
            }
        }
    }

    private static final Lazy<LRUCache<String, Object>> instance = new Lazy<>(() -> new LRUCache<>(200, 120));

    public static LRUCache<String, Object> getInstance() {
        return instance.getValue();
    }

    public static Object getOrStore(Class caller, String key, Function<String, Object> supplier) {
        require(caller, key, supplier);

        String k = App.cacheKey(caller.getName() + key);
        return getInstance().getOrAdd(k, p -> supplier.apply(key));
    }

    private final Map<TK, CacheItem> cache;
    private int                      maxCapacity;
    private int                      expireSeconds;
    private Consumer<TV>             expireCallback;
    private Future                   future;

    public LRUCache(int maxSize, int expireSecondsAfterAccess) {
        this(maxSize, expireSecondsAfterAccess, 20 * 1000, null);
    }

    public LRUCache(int maxSize, int expireSecondsAfterAccess, long checkPeriod, Consumer<TV> removeCallback) {
        cache = Collections.synchronizedMap(new LinkedHashMap<TK, CacheItem>(maxSize + 1, .75F, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<TK, CacheItem> eldest) {
                boolean remove = size() > maxCapacity;
                if (remove) {
                    eldest.getValue().callback();
                }
                return remove;
            }
        });
        maxCapacity = maxSize;
        expireSeconds = expireSecondsAfterAccess;
        expireCallback = removeCallback;
        future = TaskFactory.schedule(() -> {
            for (Map.Entry<TK, CacheItem> entry : NQuery.of(cache.entrySet()).where(p -> p.getValue().expireSeconds > -1
                    && DateTime.utcNow().addSeconds(-p.getValue().expireSeconds).after(p.getValue().createTime))) {
                entry.getValue().callback();
                cache.remove(entry.getKey());
            }
        }, checkPeriod);
    }

    @Override
    protected void freeObjects() {
        if (future != null) {
            future.cancel(true);
        }
        cache.clear();
    }

    public void add(TK key, TV val) {
        add(key, val, expireSeconds, expireCallback);
    }

    public void add(TK key, TV val, int expireSecondsAfterAccess, Consumer<TV> removeCallback) {
        require(key);

        cache.put(key, new CacheItem(val, expireSecondsAfterAccess, removeCallback));
    }

    public void remove(TK key) {
        remove(key, true);
    }

    public void remove(TK key, boolean destroy) {
        require(key);
        CacheItem remove = cache.remove(key);
        if (remove == null) {
            return;
        }

        AutoCloseable ac;
        if (destroy && (ac = as(remove.value, AutoCloseable.class)) != null) {
            try {
                ac.close();
            } catch (Exception ex) {
                Logger.error(ex, "Auto close error");
            }
        }
    }

    public TV get(TK key) {
        require(key);

        CacheItem item = cache.get(key);
        if (item == null) {
            return null;
        }
        item.refresh();
        return item.value;
    }

    public TV getOrAdd(TK key, Function<TK, TV> supplier) {
        require(key, supplier);

        CacheItem item = cache.get(key);
        if (item == null) {
            cache.put(key, item = new CacheItem(supplier.apply(key), expireSeconds, expireCallback));
        } else {
            item.refresh();
        }
        return item.value;
    }
}
