package org.rx.cache;

import org.apache.commons.collections4.map.LRUMap;
import org.rx.common.*;
import org.rx.beans.DateTime;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.function.Function;

import static org.rx.common.Contract.as;
import static org.rx.common.Contract.require;
import static org.rx.util.AsyncTask.TaskFactory;

public final class LRUCache<TK, TV> extends Disposable {
    private class CacheItem {
        private TV       value;
        private DateTime createTime;

        public CacheItem(TV value) {
            this.value = value;
            this.createTime = DateTime.utcNow();
        }
    }

    private static final Lazy<LRUCache<String, Object>> instance = new Lazy<>(() -> new LRUCache<>(200, 120, 64));

    public static LRUCache<String, Object> getInstance() {
        return instance.getValue();
    }

    public static Object getOrStore(Class caller, String key, Function<String, Object> supplier) {
        require(caller, key, supplier);

        String k = App.cacheKey(caller.getName() + key);
        return getInstance().getOrAdd(k, p -> supplier.apply(key));
    }

    private final Map<TK, CacheItem> cache;
    private Future                   future;

    public LRUCache() {
        this(200, 120, 60 * 1000);
    }

    public LRUCache(int maxSize, int expireSecondsAfterAccess, long checkPeriod) {
        cache = Collections.synchronizedMap(new LRUMap<>(maxSize));
        if (expireSecondsAfterAccess > -1) {
            future = TaskFactory.schedule(() -> {
                for (TK k : NQuery.of(cache.entrySet()).where(
                        p -> DateTime.utcNow().addSeconds(-expireSecondsAfterAccess).after(p.getValue().createTime))
                        .select(p -> p.getKey())) {
                    cache.remove(k);
                    Logger.debug("LRUCache remove {}", k);
                }
            }, checkPeriod);
        }
    }

    @Override
    protected void freeObjects() {
        if (future != null) {
            future.cancel(true);
        }
        cache.clear();
    }

    public void add(TK key, TV val) {
        require(key);

        cache.put(key, new CacheItem(val));
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
        item.createTime = DateTime.utcNow();
        return item.value;
    }

    public TV getOrAdd(TK key, Function<TK, TV> supplier) {
        require(key, supplier);

        CacheItem item = cache.get(key);
        if (item == null) {
            cache.put(key, item = new CacheItem(supplier.apply(key)));
        } else {
            item.createTime = DateTime.utcNow();
        }
        return item.value;
    }
}
