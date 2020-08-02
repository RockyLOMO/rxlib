package org.rx.core.cache;

import com.google.common.cache.CacheBuilder;
import lombok.SneakyThrows;
import org.rx.core.Cache;
import org.rx.core.SystemException;
import org.rx.util.function.BiFunc;

import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.rx.core.Contract.*;

class LocalCache<TK, TV> implements Cache<TK, TV> {
    private final com.google.common.cache.Cache<TK, TV> cache = CacheBuilder.newBuilder().maximumSize(MAX_INT).expireAfterAccess(CONFIG.getLruCacheExpireMinutes(), TimeUnit.MINUTES).build();

    @Override
    public int size() {
        return (int) cache.size();
    }

    @Override
    public Set<TK> keySet() {
        return cache.asMap().keySet();
    }

    @Override
    public void put(TK key, TV val) {
        cache.put(key, val);
    }

    @Override
    public void remove(TK key, boolean destroy) {
        TV val = cache.getIfPresent(key);
        if (val == null) {
            return;
        }

        cache.invalidate(key);
        if (destroy) {
            tryClose(val);
        }
    }

    @Override
    public void clear() {
        cache.invalidateAll();
    }

    @Override
    public TV get(TK key) {
        return cache.getIfPresent(key);
    }

    @SneakyThrows
    @Override
    public TV get(TK key, BiFunc<TK, TV> supplier) {
        return cache.get(key, () -> {
            try {
                return supplier.invoke(key);
            } catch (Throwable e) {
                throw SystemException.wrap(e);
            }
        });
    }
}
