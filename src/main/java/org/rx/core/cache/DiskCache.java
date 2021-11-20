package org.rx.core.cache;

import com.github.benmanes.caffeine.cache.RemovalCause;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.keyvalue.DefaultMapEntry;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.rx.bean.DateTime;
import org.rx.core.*;
import org.rx.io.*;

import java.io.Serializable;
import java.util.*;

import static org.rx.core.App.eq;

@Slf4j
public class DiskCache<TK, TV> implements Cache<TK, TV>, EventTarget<DiskCache<TK, TV>> {
    static {
        Container.INSTANCE.register(DiskCache.class, new DiskCache<>());
    }

    public final Delegate<DiskCache<TK, TV>, NEventArgs<Map.Entry<TK, TV>>> onExpired = Delegate.create();
    final Cache<TK, DiskCacheItem<TV>> cache = new MemoryCache<>(b -> b.maximumSize(Short.MAX_VALUE).removalListener(this::onRemoval));
    @Getter(lazy = true)
    private final KeyValueStore<TK, DiskCacheItem<TV>> store = KeyValueStore.getInstance();

    private void onRemoval(@Nullable TK key, DiskCacheItem<TV> item, @NonNull RemovalCause removalCause) {
//        log.info("onRemoval {} {}", key, removalCause);
        if (key == null || item == null || item.value == null
                || removalCause == RemovalCause.REPLACED || removalCause == RemovalCause.EXPLICIT
                || item.expire.before(DateTime.utcNow())) {
            return;
        }
        if (!(key instanceof Serializable && item.value instanceof Serializable)) {
            return;
        }
        getStore().put(key, item);
        log.info("onRemoval[{}] copy to store {} {}", removalCause, key, item.expire);
    }

    @Override
    public int size() {
        return cache.size() + getStore().size();
    }

    @Override
    public boolean containsKey(Object key) {
        return cache.containsKey(key) || getStore().containsKey(key);
    }

    @Override
    public boolean containsValue(Object value) {
        return cache.containsValue(value)
//                || getStore().containsValue(value)
                ;
    }

    @Override
    public TV get(Object key) {
        DiskCacheItem<TV> item = cache.get(key);
        if (item == null) {
            item = getStore().get(key);
        }
        return unwrap((TK) key, item);
    }

    private TV unwrap(TK key, DiskCacheItem<TV> item) {
        if (item == null) {
            return null;
        }
        DateTime utc = DateTime.utcNow();
//        log.info("check {} < NOW[{}]", item.expire, utc);
        if (item.expire.before(utc)) {
            remove(key);
            if (onExpired == null) {
                return null;
            }
            NEventArgs<Map.Entry<TK, TV>> args = new NEventArgs<>(new DefaultMapEntry<>(key, item.value));
            raiseEvent(onExpired, args);
            return args.getValue().getValue();
        }
        if (item.slidingSeconds > 0) {
            item.expire = utc.addSeconds(item.slidingSeconds);
            cache.put(key, item, CacheExpiration.sliding(item.slidingSeconds));
        }
        return item.value;
    }

    @Override
    public TV put(TK key, TV value, CacheExpiration expiration) {
        if (expiration == null) {
            expiration = CacheExpiration.NON_EXPIRE;
        }

        DiskCacheItem<TV> item = new DiskCacheItem<>(value, expiration.getSlidingExpiration());
        if (!eq(expiration.getAbsoluteExpiration(), CacheExpiration.NON_EXPIRE.getAbsoluteExpiration())) {
            item.expire = expiration.getAbsoluteExpiration();
        } else if (expiration.getSlidingExpiration() != CacheExpiration.NON_EXPIRE.getSlidingExpiration()) {
            item.expire = DateTime.utcNow().addMinutes(expiration.getSlidingExpiration());
        } else {
            item.expire = DateTime.MAX;
        }
        DiskCacheItem<TV> old = cache.put(key, item, expiration);
        return unwrap(key, old);
    }

    @Override
    public TV remove(Object key) {
        DiskCacheItem<TV> remove = cache.remove(key);
        if (remove == null) {
            remove = getStore().remove(key);
        }
        return remove == null ? null : remove.value;
    }

    @Override
    public void clear() {
        cache.clear();
        getStore().clear();
    }

    @Override
    public Set<TK> keySet() {
        return cache.keySet();
    }

    @Override
    public Collection<TV> values() {
        return NQuery.of(keySet()).select(k -> get(k)).where(Objects::nonNull).toList();
    }

    @Override
    public Set<Map.Entry<TK, TV>> entrySet() {
        return NQuery.of(keySet()).select(k -> {
            TV v = get(k);
            return v == null ? null : (Map.Entry<TK, TV>) new DefaultMapEntry<>(k, v);
        }).where(Objects::nonNull).toSet();
    }
}
