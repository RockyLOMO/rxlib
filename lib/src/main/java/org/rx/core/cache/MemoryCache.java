package org.rx.core.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Policy;
import com.github.benmanes.caffeine.cache.Scheduler;
import lombok.SneakyThrows;
import org.rx.core.*;
import org.rx.util.function.BiAction;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.rx.core.Extends.require;

public class MemoryCache<TK, TV> implements Cache<TK, TV> {
    static {
        IOC.register(MemoryCache.class, new MemoryCache<>());
    }

    public static Caffeine<Object, Object> weightBuilder(Caffeine<Object, Object> b, float memoryPercent, int entryBytes) {
        require(memoryPercent, 0 < memoryPercent && memoryPercent <= 1);

        return weightBuilder(b, (long) (Runtime.getRuntime().maxMemory() * memoryPercent), entryBytes);
    }

    public static Caffeine<Object, Object> weightBuilder(Caffeine<Object, Object> b, long maxBytes, int entryBytes) {
        return b.maximumWeight(maxBytes).weigher((k, v) -> entryBytes);
    }

    static Caffeine<Object, Object> rootBuilder() {
        return Caffeine.newBuilder().executor(Tasks.executor()).scheduler(Scheduler.forScheduledExecutorService(Tasks.timer()));
    }

    final com.github.benmanes.caffeine.cache.Cache<TK, TV> cache;
    final Policy.VarExpiration<TK, TV> expireVariably;

    public MemoryCache() {
        this(b -> b.maximumSize(RxConfig.INSTANCE.getCache().getMaxItemSize()));
    }

    @SneakyThrows
    public MemoryCache(BiAction<Caffeine<Object, Object>> onBuild) {
        Caffeine<Object, Object> builder = rootBuilder().expireAfter(new CaffeineExpiry());
        if (onBuild != null) {
            onBuild.invoke(builder);
        }
        cache = builder.build();
        expireVariably = cache.policy().expireVariably().get();
    }

    public void setExpire(TK key, long millis) {
        expireVariably.setExpiresAfter(key, millis, TimeUnit.MILLISECONDS);
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
    public TV put(TK key, TV value, CachePolicy policy) {
        TV oldValue = cache.asMap().put(key, value);
        if (policy != null) {
            setExpire(key, TimeUnit.NANOSECONDS.toMillis(CaffeineExpiry.computeNanos(value, -1)));
        }
        return oldValue;
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
    public TV replace(TK key, TV value) {
        return cache.asMap().replace(key, value);
    }

    @Override
    public boolean replace(TK key, TV oldValue, TV newValue) {
        return cache.asMap().replace(key, oldValue, newValue);
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
