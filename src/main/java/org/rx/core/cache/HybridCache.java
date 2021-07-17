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
    static class ValueWrapper<TV> implements Serializable {
        private static final long serialVersionUID = -7742074465897857966L;
        final TV value;
        final int slidingMinutes;
        DateTime expire;
    }

    public static Cache DEFAULT = new HybridCache<>();

    public volatile BiConsumer<HybridCache<TK, TV>, NEventArgs<Map.Entry<TK, TV>>> onExpired;
    final Cache<TK, ValueWrapper<TV>> cache;
    @Getter(lazy = true)
    private final KeyValueStore<TK, ValueWrapper<TV>> store = KeyValueStore.getInstance();

    public HybridCache() {
//        long ttl = 5;
        long ttl = 60 * 2;
        cache = new CaffeineCache<>(CaffeineCache.builder(ttl)
                .softValues().maximumSize(Short.MAX_VALUE).removalListener(this::onRemoval).build());
    }

    private void onRemoval(@Nullable TK key, HybridCache.ValueWrapper<TV> wrapper, @NonNull RemovalCause removalCause) {
//        log.info("onRemoval {} {}", key, removalCause);
        if (key == null || wrapper == null || wrapper.value == null
                || removalCause == RemovalCause.EXPLICIT || wrapper.expire.before(DateTime.utcNow())) {
            return;
        }
        if (!(key instanceof Serializable && wrapper.value instanceof Serializable)) {
            return;
        }
        getStore().put(key, wrapper);
        log.info("onRemoval[{}] copy to store {} {}", removalCause, key, wrapper.expire);
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
        ValueWrapper<TV> valueWrapper = cache.get(key);
        if (valueWrapper == null) {
            valueWrapper = getStore().get(key);
        }
        return unwrap((TK) key, valueWrapper);
    }

    private TV unwrap(TK key, ValueWrapper<TV> wrapper) {
        if (wrapper == null) {
            return null;
        }
        DateTime utc = DateTime.utcNow();
//        log.info("check {} < NOW[{}]", wrapper.expire, utc);
        if (wrapper.expire.before(utc)) {
            remove(key);
            if (onExpired == null) {
                return null;
            }
            NEventArgs<Map.Entry<TK, TV>> args = new NEventArgs<>(new DefaultMapEntry<>(key, wrapper.value));
            raiseEvent(onExpired, args);
            return args.getValue().getValue();
        }
        if (wrapper.slidingMinutes > 0) {
            wrapper.expire = utc.addMinutes(wrapper.slidingMinutes);
            cache.put(key, wrapper);
        }
        return wrapper.value;
    }

    @Override
    public TV put(TK key, TV value, CacheExpirations expirations) {
        if (expirations == null) {
            expirations = CacheExpirations.NON_EXPIRE;
        }

        ValueWrapper<TV> wrapper = new ValueWrapper<>(value, expirations.getSlidingExpiration());
        if (!eq(expirations.getAbsoluteExpiration(), CacheExpirations.NON_EXPIRE.getAbsoluteExpiration())) {
            wrapper.expire = expirations.getAbsoluteExpiration();
        } else if (expirations.getSlidingExpiration() != CacheExpirations.NON_EXPIRE.getSlidingExpiration()) {
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
