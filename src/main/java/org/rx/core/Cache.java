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

    static <TK, TV> TV getOrSet(@NonNull TK key, @NonNull BiFunc<TK, TV> loadingFunc, CacheExpirations expirations) {
        return Cache.<TK, TV>getInstance(App.getConfig().getDefaultCache()).get(key, loadingFunc, expirations);
    }

    static <TK, TV> Cache<TK, TV> getInstance() {
        return getInstance(App.getConfig().getDefaultCache());
    }

    static <TK, TV> Cache<TK, TV> getInstance(String name) {
        return Container.getInstance().getOrRegister(name, () -> {
            switch (name) {
                case LOCAL_CACHE:
                    return (Cache<TK, TV>) CaffeineCache.SLIDING_CACHE;
                case DISTRIBUTED_CACHE:
                    return (Cache<TK, TV>) PersistentCache.DEFAULT;
                default:
                    throw new InvalidException("Cache provider %s not exists", name);
            }
        });
    }

    default TV get(TK key, BiFunc<TK, TV> loadingFunc) {
        return computeIfAbsent(key, k -> {
            try {
                TV val = loadingFunc.invoke(k);
                if (val == null) {
                    throw new InvalidException("Loading result is null");
                }
                return val;
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
