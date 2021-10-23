package org.rx.core;

import lombok.NonNull;
import lombok.SneakyThrows;
import org.rx.bean.AbstractMap;
import org.rx.core.cache.MemoryCache;
import org.rx.core.cache.DiskCache;
import org.rx.core.cache.ThreadCache;
import org.rx.exception.InvalidException;
import org.rx.util.function.BiFunc;

import static org.rx.core.App.*;

public interface Cache<TK, TV> extends AbstractMap<TK, TV> {
    String MEMORY_CACHE = "_MC";
    String THREAD_CACHE = "_TC";
    String DISTRIBUTED_CACHE = "_DC";

    static <TK, TV> TV getOrSet(TK key, BiFunc<TK, TV> loadingFunc) {
        return getOrSet(key, loadingFunc, CacheExpiration.NON_EXPIRE);
    }

    static <TK, TV> TV getOrSet(TK key, BiFunc<TK, TV> loadingFunc, String cacheName) {
        return getOrSet(key, loadingFunc, CacheExpiration.NON_EXPIRE, cacheName);
    }

    static <TK, TV> TV getOrSet(@NonNull TK key, @NonNull BiFunc<TK, TV> loadingFunc, CacheExpiration expiration) {
        return getOrSet(key, loadingFunc, expiration, App.getConfig().getDefaultCache());
    }

    static <TK, TV> TV getOrSet(@NonNull TK key, @NonNull BiFunc<TK, TV> loadingFunc, CacheExpiration expiration, String cacheName) {
        return Cache.<TK, TV>getInstance(cacheName).get(key, loadingFunc, expiration);
    }

    static <TK, TV> Cache<TK, TV> getInstance(String cacheName) {
        return Container.getInstance().getOrRegister(cacheName, () -> {
            switch (cacheName) {
                case MEMORY_CACHE:
                    return (Cache<TK, TV>) MemoryCache.DEFAULT;
                case DISTRIBUTED_CACHE:
                    return (Cache<TK, TV>) DiskCache.DEFAULT;
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
    default TV get(TK key, BiFunc<TK, TV> loadingFunc, CacheExpiration expiration) {
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

    @Override
    default TV put(TK key, TV value) {
        return put(key, value, null);
    }

    TV put(TK key, TV value, CacheExpiration expiration);

    default TV remove(TK key, boolean destroy) {
        TV v = remove(key);
        if (destroy) {
            tryClose(v);
        }
        return v;
    }
}
