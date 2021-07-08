package org.rx.core.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.Scheduler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.keyvalue.AbstractMapEntry;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.rx.bean.DateTime;
import org.rx.core.Cache;
import org.rx.core.CacheExpirations;
import org.rx.core.NQuery;
import org.rx.core.Tasks;
import org.rx.io.*;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
public class HybridCache<TK, TV> implements Cache<TK, TV> {
    @RequiredArgsConstructor
    static class ValueWrapper<TV> implements Serializable {
        private static final long serialVersionUID = -7742074465897857966L;
        final TV value;
        final int slidingMinutes;
        DateTime expire;
    }

    public static Cache DEFAULT = new HybridCache<>();

    final Cache<TK, ValueWrapper<TV>> cache;
    final KeyValueStore<TK, ValueWrapper<TV>> store;

    public HybridCache() {
        cache = new CaffeineCache<>(Caffeine.newBuilder().executor(Tasks.pool()).scheduler(Scheduler.disabledScheduler())
                .softValues().expireAfterAccess(1, TimeUnit.MINUTES).maximumSize(Short.MAX_VALUE)
                .removalListener(this::onRemoval).build());
        store = KeyValueStore.getInstance();
    }

    private void onRemoval(@Nullable TK key, HybridCache.ValueWrapper<TV> wrapper, @NonNull RemovalCause removalCause) {
        log.info("onRemoval {} {}", key, removalCause);
        if (key == null || wrapper == null || wrapper.value == null
                || removalCause == RemovalCause.EXPLICIT || wrapper.expire.before(DateTime.utcNow())) {
            return;
        }
        if (!(key instanceof Serializable && wrapper.value instanceof Serializable)) {
            return;
        }
        store.put(key, wrapper);
//        log.debug("switch to store {}", key);
    }

    @Override
    public boolean isEmpty() {
        return size() == 0;
    }

    @Override
    public int size() {
        return cache.size() + store.size();
    }

    @Override
    public boolean containsKey(Object key) {
        return cache.containsKey(key) || store.containsKey(key);
    }

    @Override
    public boolean containsValue(Object value) {
        return cache.containsValue(value) || store.containsValue(value);
    }

    @Override
    public TV get(Object key) {
        ValueWrapper<TV> valueWrapper = cache.get(key);
        if (valueWrapper == null) {
            valueWrapper = store.get(key);
        }
        return unwrap((TK) key, valueWrapper);
    }

    private TV unwrap(TK key, ValueWrapper<TV> wrapper) {
        if (wrapper == null) {
            return null;
        }
        DateTime utc = DateTime.utcNow();
        if (wrapper.expire.before(utc)) {
            remove(key);
            return null;
        }
        if (wrapper.slidingMinutes > 0) {
            wrapper.expire = utc.addMinutes(wrapper.slidingMinutes);
            cache.put(key, wrapper);
        }
        return wrapper.value;
    }

    @Override
    public TV put(TK key, TV value) {
        return put(key, value, CacheExpirations.NON_EXPIRE);
    }

    @Override
    public TV put(TK key, TV value, CacheExpirations expirations) {
        if (expirations == null) {
            expirations = CacheExpirations.NON_EXPIRE;
        }

        ValueWrapper<TV> wrapper = new ValueWrapper<>(value, expirations.getSlidingExpiration());
        if (expirations.getAbsoluteExpiration() != null) {
            wrapper.expire = expirations.getAbsoluteExpiration();
        } else if (expirations.getSlidingExpiration() > 0) {
            wrapper.expire = DateTime.utcNow().addMinutes(expirations.getSlidingExpiration());
        } else {
            wrapper.expire = DateTime.MAX;
        }
        ValueWrapper<TV> old = cache.put(key, wrapper);
        return unwrap(key, old);
    }

    @Override
    public TV remove(Object key) {
        ValueWrapper<TV> remove = cache.remove(key);
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
        return NQuery.of(keySet()).select(k -> get(k)).where(Objects::nonNull).toList();
    }

    @Override
    public Set<Map.Entry<TK, TV>> entrySet() {
        return NQuery.of(keySet()).select(k -> {
            TV v = get(k);
            return v == null ? null : (Map.Entry<TK, TV>) new AbstractMapEntry<TK, TV>(k, v) {
            };
        }).where(Objects::nonNull).toSet();
    }
}
