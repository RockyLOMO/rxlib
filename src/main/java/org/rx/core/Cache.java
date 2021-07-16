package org.rx.core;

import lombok.NonNull;
import lombok.SneakyThrows;
import org.rx.core.cache.CaffeineCache;
import org.rx.core.cache.HybridCache;
import org.rx.core.cache.ThreadCache;
import org.rx.core.exception.InvalidException;
import org.rx.util.function.BiFunc;

import java.util.*;

import static org.rx.core.App.*;

public interface Cache<TK, TV> extends Map<TK, TV> {
    String LOCAL_CACHE = "localCache";
    String THREAD_CACHE = "threadCache";
    String DISTRIBUTED_CACHE = "distributedCache";

    static <TK, TV> TV getOrSet(TK key, BiFunc<TK, TV> loadingFunc) {
        return getOrSet(key, loadingFunc, CacheExpirations.NON_EXPIRE);
    }

    static <TK, TV> TV getOrSet(TK key, BiFunc<TK, TV> loadingFunc, String cacheName) {
        return getOrSet(key, loadingFunc, CacheExpirations.NON_EXPIRE, cacheName);
    }

    static <TK, TV> TV getOrSet(@NonNull TK key, @NonNull BiFunc<TK, TV> loadingFunc, CacheExpirations expirations) {
        return getOrSet(key, loadingFunc, expirations, App.getConfig().getDefaultCache());
    }

    static <TK, TV> TV getOrSet(@NonNull TK key, @NonNull BiFunc<TK, TV> loadingFunc, CacheExpirations expirations, String cacheName) {
        return Cache.<TK, TV>getInstance(cacheName).get(key, loadingFunc, expirations);
    }

    static <TK, TV> Cache<TK, TV> getInstance(String cacheName) {
        return Container.getInstance().getOrRegister(cacheName, () -> {
            switch (cacheName) {
                case LOCAL_CACHE:
                    return (Cache<TK, TV>) CaffeineCache.SLIDING_CACHE;
                case DISTRIBUTED_CACHE:
                    return (Cache<TK, TV>) HybridCache.DEFAULT;
                case THREAD_CACHE:
                    return (Cache<TK, TV>) ThreadCache.getInstance();
                default:
                    throw new InvalidException("Cache provider %s not exists", cacheName);
            }
        });
    }

    default TV get(TK key, BiFunc<TK, TV> loadingFunc) {
        return get(key, loadingFunc, null);
    }

    @SneakyThrows
    default TV get(TK key, BiFunc<TK, TV> loadingFunc, CacheExpirations expiration) {
        TV v;
        if ((v = get(key)) == null) {
            TV newValue;
            if ((newValue = loadingFunc.invoke(key)) != null) {
                put(key, newValue, expiration);
                return newValue;
            }
        }
        return v;
    }

    default Map<TK, TV> getAll(Iterable<TK> keys) {
        return NQuery.of(keys).toMap(k -> k, this::get);
    }

    default TV put(TK key, TV value) {
        return put(key, value, null);
    }

    TV put(TK key, TV value, CacheExpirations expiration);

    default TV remove(TK key, boolean destroy) {
        TV v = remove(key);
        if (destroy) {
            tryClose(v);
        }
        return v;
    }

    default void removeAll(Iterable<TK> keys) {
        for (TK k : keys) {
            remove(k);
        }
    }

    @Override
    default boolean isEmpty() {
        return size() == 0;
    }

    @Override
    default boolean containsKey(Object key) {
        return get(key) != null;
    }

    @Override
    default boolean containsValue(Object value) {
        return values().contains(value);
    }

    @Override
    default void putAll(Map<? extends TK, ? extends TV> m) {
        for (Entry<? extends TK, ? extends TV> entry : m.entrySet()) {
            put(entry.getKey(), entry.getValue());
        }
    }
}
