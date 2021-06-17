package org.rx.core.cache;

import com.google.common.cache.CacheBuilder;
import lombok.SneakyThrows;
import org.rx.core.Cache;
import org.rx.util.function.BiFunc;

import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.rx.core.App.*;

public class LocalCache<TK, TV> implements Cache<TK, TV> {
    private final com.google.common.cache.Cache<TK, TV> cache;

    public LocalCache() {
        this(1);
    }

    public LocalCache(int expireMinutes) {
        cache = CacheBuilder.newBuilder().maximumSize(Short.MAX_VALUE)
                .expireAfterAccess(expireMinutes, TimeUnit.MINUTES).build(); //expireAfterAccess 包含expireAfterWrite
    }

    @Override
    public long size() {
        return cache.size();
    }

    @Override
    public Set<TK> keySet() {
        return cache.asMap().keySet();
    }

    @Override
    public synchronized TV put(TK key, TV val) {
        TV v = cache.getIfPresent(key);
        if (v == null) {
            return null;
        }
        cache.put(key, val);
        return v;
    }

    @Override
    public synchronized TV remove(TK key) {
        TV v = cache.getIfPresent(key);
        if (v == null) {
            return null;
        }
        cache.invalidate(key);
        return v;
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
        return cache.get(key, () -> sneakyInvoke(() -> supplier.invoke(key)));
    }
}
