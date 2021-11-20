package org.rx.core;

import lombok.NonNull;
import lombok.SneakyThrows;
import org.rx.bean.AbstractMap;
import org.rx.core.cache.DiskCache;
import org.rx.core.cache.MemoryCache;
import org.rx.core.cache.ThreadCache;
import org.rx.util.function.BiFunc;

import static org.rx.core.App.*;

public interface Cache<TK, TV> extends AbstractMap<TK, TV> {
    Class<MemoryCache> MEMORY_CACHE = MemoryCache.class;
    Class<DiskCache> DISK_CACHE = DiskCache.class;
    Class<ThreadCache> THREAD_CACHE = ThreadCache.class;
    Class<Cache> MAIN_CACHE = Cache.class;

    static <TK, TV> TV getOrSet(TK key, BiFunc<TK, TV> loadingFunc) {
        return getOrSet(key, loadingFunc, CacheExpiration.NON_EXPIRE);
    }

    static <TK, TV> TV getOrSet(TK key, BiFunc<TK, TV> loadingFunc, Class<?> cacheName) {
        return getOrSet(key, loadingFunc, CacheExpiration.NON_EXPIRE, cacheName);
    }

    static <TK, TV> TV getOrSet(@NonNull TK key, @NonNull BiFunc<TK, TV> loadingFunc, CacheExpiration expiration) {
        return getOrSet(key, loadingFunc, expiration, MAIN_CACHE);
    }

    static <TK, TV> TV getOrSet(@NonNull TK key, @NonNull BiFunc<TK, TV> loadingFunc, CacheExpiration expiration, Class<?> cacheName) {
        return Cache.<TK, TV>getInstance(cacheName).get(key, loadingFunc, expiration);
    }

    static <TK, TV> Cache<TK, TV> getInstance(Class<?> cacheName) {
        return (Cache<TK, TV>) Container.INSTANCE.get(cacheName);
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
