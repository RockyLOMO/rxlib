package org.rx.core;

import lombok.NonNull;
import org.rx.core.cache.CaffeineCache;
import org.rx.core.cache.PersistentCache;
import org.rx.core.exception.ApplicationException;
import org.rx.core.exception.InvalidException;
import org.rx.util.function.BiFunc;

import java.util.*;
import java.util.concurrent.ConcurrentMap;

import static org.rx.core.App.*;

public interface Cache<TK, TV> extends ConcurrentMap<TK, TV> {
    String LOCAL_CACHE = "localCache";
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
                    return (Cache<TK, TV>) PersistentCache.DEFAULT;
                default:
                    throw new InvalidException("Cache provider %s not exists", cacheName);
            }
        });
    }

    /**
     * computeIfAbsent
     *
     * @param key
     * @param loadingFunc result可以为null
     * @return
     */
    default TV get(TK key, BiFunc<TK, TV> loadingFunc) {
        return computeIfAbsent(key, k -> {
            try {
                return loadingFunc.invoke(k);
            } catch (Throwable e) {
                throw ApplicationException.sneaky(e);
            }
        });
    }

    default TV get(TK key, BiFunc<TK, TV> loadingFunc, CacheExpirations expirations) {
        return get(key, loadingFunc);
    }

    Map<TK, TV> getAll(Iterable<TK> keys, BiFunc<Set<TK>, Map<TK, TV>> loadingFunc);

    Map<TK, TV> getAll(Iterable<TK> keys);

    default TV put(TK key, TV value, CacheExpirations expirations) {
        return put(key, value);
    }

    default TV remove(TK key, boolean destroy) {
        TV v = remove(key);
        if (destroy) {
            tryClose(v);
        }
        return v;
    }

    void removeAll(Iterable<TK> keys);
}
