package org.rx.core.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Scheduler;
import lombok.RequiredArgsConstructor;
import org.rx.core.Cache;
import org.rx.core.Tasks;
import org.rx.core.exception.ApplicationException;
import org.rx.util.function.BiFunc;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@RequiredArgsConstructor
public class CaffeineCache<TK, TV> implements Cache<TK, TV> {
    public static final Cache SLIDING_CACHE = new CaffeineCache<>(Caffeine.newBuilder().executor(Tasks.pool()).scheduler(Scheduler.disabledScheduler())
            .softValues().expireAfterAccess(2, TimeUnit.MINUTES)
            .build());

    final com.github.benmanes.caffeine.cache.Cache<TK, TV> cache;

    @Override
    public boolean isEmpty() {
        return cache.estimatedSize() == 0;
    }

    @Override
    public int size() {
        return (int) cache.estimatedSize();
    }

    @Override
    public boolean containsKey(Object key) {
        return cache.getIfPresent(key) != null;
    }

    @Override
    public boolean containsValue(Object value) {
        return cache.asMap().containsValue(value);
    }

    @Override
    public TV get(Object key) {
        return cache.getIfPresent(key);
    }

    @Override
    public Map<TK, TV> getAll(Iterable<TK> keys) {
        return cache.getAllPresent(keys);
    }

    @Override
    public Map<TK, TV> getAll(Iterable<TK> keys, BiFunc<Set<TK>, Map<TK, TV>> loadingFunc) {
        return cache.getAll(keys, ks -> {
            try {
                return loadingFunc.invoke((Set<TK>) ks);
            } catch (Throwable e) {
                throw ApplicationException.sneaky(e);
            }
        });
    }

    @Override
    public TV put(TK key, TV value) {
        return cache.asMap().put(key, value);
    }

    @Override
    public void putAll(Map<? extends TK, ? extends TV> m) {
        cache.putAll(m);
    }

    @Override
    public TV putIfAbsent(TK key, TV value) {
        return cache.asMap().putIfAbsent(key, value);
    }

    @Override
    public boolean replace(TK key, TV oldValue, TV newValue) {
        return cache.asMap().replace(key, oldValue, newValue);
    }

    @Override
    public TV replace(TK key, TV value) {
        return cache.asMap().replace(key, value);
    }

    @Override
    public TV remove(Object key) {
        return cache.asMap().remove(key);
    }

    @Override
    public boolean remove(Object key, Object value) {
        return cache.asMap().remove(key, value);
    }

    @Override
    public void removeAll(Iterable<TK> keys) {
        cache.invalidateAll(keys);
    }

    @Override
    public void clear() {
        cache.invalidateAll();
    }

    @Override
    public Set<TK> keySet() {
        return cache.asMap().keySet();
    }

    @Override
    public Collection<TV> values() {
        return cache.asMap().values();
    }

    @Override
    public Set<Entry<TK, TV>> entrySet() {
        return cache.asMap().entrySet();
    }
}
