package org.rx.core;

import lombok.NonNull;
import lombok.SneakyThrows;
import org.rx.bean.AbstractMap;
import org.rx.core.cache.MemoryCache;
import org.rx.util.function.BiFunc;

import static org.rx.core.Constants.NON_RAW_TYPES;

public interface Cache<TK, TV> extends AbstractMap<TK, TV> {
    Object NULL_VALUE = new Object();

    static <TK, TV> TV getOrSet(TK key, BiFunc<TK, TV> loadingFunc) {
        return getOrSet(key, loadingFunc, null);
    }

    static <TK, TV> TV getOrSet(@NonNull TK key, @NonNull BiFunc<TK, TV> loadingFunc, CachePolicy expiration) {
        return Cache.<TK, TV>getInstance().get(key, loadingFunc, expiration);
    }

    static <TK, TV> Cache<TK, TV> getInstance() {
        return IOC.get(Cache.class);
    }

    /**
     * @param cacheType MEMORY_CACHE, DISK_CACHE
     * @param <TK>
     * @param <TV>
     * @return
     */
    @SuppressWarnings(NON_RAW_TYPES)
    static <TK, TV> Cache<TK, TV> getInstance(Class<? extends Cache> cacheType) {
        return IOC.get(cacheType, (Class) MemoryCache.class);
    }

    default TV get(TK key, BiFunc<TK, TV> loadingFunc) {
        return get(key, loadingFunc, null);
    }

    @SneakyThrows
    default TV get(TK key, BiFunc<TK, TV> loadingFunc, CachePolicy policy) {
        TV v;
        if ((v = get(key)) == null) {
            TV newValue;
            if ((newValue = loadingFunc.invoke(key)) != null) {
                put(key, newValue, policy);
                return newValue;
            }
        }
        return v;
    }

    @Override
    default TV put(TK key, TV value) {
        return put(key, value, null);
    }

    TV put(TK key, TV value, CachePolicy policy);
}
