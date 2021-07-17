package org.rx.core.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Scheduler;
import lombok.RequiredArgsConstructor;
import org.rx.core.Cache;
import org.rx.core.CacheExpirations;
import org.rx.core.Tasks;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.rx.core.App.require;

@RequiredArgsConstructor
public class CaffeineCache<TK, TV> implements Cache<TK, TV> {
    public static final Cache SLIDING_CACHE = new CaffeineCache<>(builder(60)
            .softValues().build());

    public static Caffeine<Object, Object> builder(long slidingSeconds) {
        return Caffeine.newBuilder().executor(Tasks.pool())
                .scheduler(Scheduler.forScheduledExecutorService(Tasks.scheduler()))
                .expireAfterAccess(slidingSeconds, TimeUnit.SECONDS);
    }

    public static Caffeine<Object, Object> builder(float memoryPercent, int entryWeigh) {
        require(memoryPercent, 0 < memoryPercent && memoryPercent <= 1);

        return Caffeine.newBuilder().executor(Tasks.pool())
                .scheduler(Scheduler.forScheduledExecutorService(Tasks.scheduler()))
                .maximumWeight((long) (Runtime.getRuntime().maxMemory() * memoryPercent))
                .weigher((k, v) -> entryWeigh);
    }

    final com.github.benmanes.caffeine.cache.Cache<TK, TV> cache;

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
    public TV put(TK key, TV value, CacheExpirations expiration) {
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
