package org.rx.core.cache;

import com.github.benmanes.caffeine.cache.RemovalCause;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.keyvalue.DefaultMapEntry;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.rx.bean.DateTime;
import org.rx.core.*;
import org.rx.io.*;

import java.io.Serializable;
import java.util.*;
import java.util.function.BiConsumer;

import static org.rx.core.App.eq;

@Slf4j
public class HybridCache<TK, TV> implements Cache<TK, TV>, EventTarget<HybridCache<TK, TV>> {
    @RequiredArgsConstructor
    static class CacheItem<TV> implements Serializable {
        private static final long serialVersionUID = -7742074465897857966L;
        final TV value;
        final int slidingMinutes;
        DateTime expire;
    }

    public static Cache DEFAULT = new HybridCache<>();

    public volatile BiConsumer<HybridCache<TK, TV>, NEventArgs<Map.Entry<TK, TV>>> onExpired;
    final Cache<TK, CacheItem<TV>> cache;
    @Getter(lazy = true)
    private final KeyValueStore<TK, CacheItem<TV>> store = KeyValueStore.getInstance();

    public HybridCache() {
//        long ttl = 5;
        long ttl = 60 * 2;
        cache = new CaffeineCache<>(CaffeineCache.builder(ttl)
                .softValues().maximumSize(Short.MAX_VALUE).removalListener(this::onRemoval).build());
    }

    private void onRemoval(@Nullable TK key, CacheItem<TV> item, @NonNull RemovalCause removalCause) {
//        log.info("onRemoval {} {}", key, removalCause);
        if (key == null || item == null || item.value == null
                || removalCause == RemovalCause.EXPLICIT || item.expire.before(DateTime.utcNow())) {
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
        CacheItem<TV> item = cache.get(key);
        if (item == null) {
            item = getStore().get(key);
        }
        return unwrap((TK) key, item);
    }

    private TV unwrap(TK key, CacheItem<TV> item) {
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
        if (item.slidingMinutes > 0) {
            item.expire = utc.addMinutes(item.slidingMinutes);
            cache.put(key, item);
        }
        return item.value;
    }

    @Override
    public TV put(TK key, TV value, CacheExpirations expirations) {
        if (expirations == null) {
            expirations = CacheExpirations.NON_EXPIRE;
        }

        CacheItem<TV> item = new CacheItem<>(value, expirations.getSlidingExpiration());
        if (!eq(expirations.getAbsoluteExpiration(), CacheExpirations.NON_EXPIRE.getAbsoluteExpiration())) {
            item.expire = expirations.getAbsoluteExpiration();
        } else if (expirations.getSlidingExpiration() != CacheExpirations.NON_EXPIRE.getSlidingExpiration()) {
            item.expire = DateTime.utcNow().addMinutes(expirations.getSlidingExpiration());
        } else {
            item.expire = DateTime.MAX;
        }
        CacheItem<TV> old = cache.put(key, item);
        return unwrap(key, old);
    }

    @Override
    public TV remove(Object key) {
        CacheItem<TV> remove = cache.remove(key);
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
