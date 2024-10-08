package org.rx.core.cache;

import com.github.benmanes.caffeine.cache.RemovalCause;
import lombok.extern.slf4j.Slf4j;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.rx.core.*;
import org.rx.io.KeyValueStore;

import java.io.Serializable;
import java.util.*;

import static org.rx.core.Constants.NON_UNCHECKED;

@Slf4j
public class DiskCache<TK, TV> implements Cache<TK, TV>, EventPublisher<DiskCache<TK, TV>> {
    static {
        IOC.register(DiskCache.class, new DiskCache<>());
    }

    public final Delegate<DiskCache<TK, TV>, Map.Entry<TK, TV>> onExpired = Delegate.create();
    final Cache<TK, DiskCacheItem<TV>> cache;
    final KeyValueStore<TK, DiskCacheItem<TV>> store;
    int defaultExpireSeconds = 60 * 60 * 24 * 365;  //1 year

    public DiskCache() {
        this(1000);
    }

    public DiskCache(int cacheSize) {
        cache = new MemoryCache<>(b -> b.maximumSize(cacheSize), this::onRemoval);
        store = (KeyValueStore) KeyValueStore.getInstance(Object.class, DiskCacheItem.class);
    }

    void onRemoval(@Nullable TK key, DiskCacheItem<TV> item, @NonNull RemovalCause removalCause) {
        if (item == null) {
            return;
        }
        log.info("onRemoval {}[{}] -> {}", key, item.getExpiration(), removalCause);
        if (item.value == null || removalCause == RemovalCause.REPLACED || removalCause == RemovalCause.EXPLICIT) {
            return;
        }
        if (item.isExpired()) {
            raiseEvent(onExpired, new AbstractMap.SimpleEntry<>(key, item.value));
            return;
        }
        if (!(key instanceof Serializable && item.value instanceof Serializable)) {
            return;
        }
        store.fastPut(key, item);
        log.info("onRemoval copy to store {} -> {}ms", key, item.getExpiration() - System.currentTimeMillis());
    }

    @Override
    public int size() {
        return cache.size() + store.size();
    }

    @Override
    public boolean containsKey(Object key) {
        return get(key) != null;
    }

    @Override
    public boolean containsValue(Object value) {
        return cache.containsValue(value) || store.containsValue(value);
    }

    @SuppressWarnings(NON_UNCHECKED)
    @Override
    public TV get(Object key) {
        boolean doRenew = false;
        DiskCacheItem<TV> item = cache.get(key);
        if (item == null) {
            item = store.get(key);
            doRenew = true;
        }
        return unwrap((TK) key, item, doRenew);
    }

    private TV unwrap(TK key, DiskCacheItem<TV> item, boolean doRenew) {
        if (item == null) {
            return null;
        }
        if (item.isExpired()) {
            remove(key);
            if (onExpired == null) {
                return null;
            }
            Map.Entry<TK, TV> args = new AbstractMap.SimpleEntry<>(key, item.value);
            raiseEvent(onExpired, args);
            return args.getValue();
        }
        if (doRenew && item.slidingRenew()) {
            cache.put(key, item);
        }
        return item.value;
    }

    @Override
    public TV put(TK key, TV value, CachePolicy policy) {
        if (policy == null) {
            policy = CachePolicy.absolute(defaultExpireSeconds);
        }
        DiskCacheItem<TV> old = cache.put(key, new DiskCacheItem<>(value, policy));
        return unwrap(key, old, false);
    }

    @Override
    public TV remove(Object key) {
        DiskCacheItem<TV> remove = cache.remove(key);
        if (remove == null) {
            remove = store.remove(key);
        }
        return remove == null ? null : remove.value;
    }

    @Override
    public void clear() {
        cache.clear();
        store.clear();
    }

    @Override
    public Set<TK> keySet() {
        return cache.keySet();
    }

    @Override
    public Collection<TV> values() {
        return Linq.from(keySet()).select(k -> get(k)).where(Objects::nonNull).toList();
    }

    @Override
    public Set<Map.Entry<TK, TV>> entrySet() {
        return Linq.from(keySet()).select(k -> {
            TV v = get(k);
            return v == null ? null : (Map.Entry<TK, TV>) new AbstractMap.SimpleEntry<>(k, v);
        }).where(Objects::nonNull).toSet();
    }
}
