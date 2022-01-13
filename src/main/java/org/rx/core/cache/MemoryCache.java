package org.rx.core.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Policy;
import com.github.benmanes.caffeine.cache.Scheduler;
import io.netty.util.internal.SystemPropertyUtil;
import lombok.SneakyThrows;
import org.rx.core.*;
import org.rx.util.function.BiAction;

import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.rx.core.App.*;

public class MemoryCache<TK, TV> implements Cache<TK, TV> {
    static {
        Container.register(MemoryCache.class, new MemoryCache<>());
    }

    public static Caffeine<Object, Object> weightBuilder(Caffeine<Object, Object> b, float memoryPercent, int entryWeigh) {
        require(memoryPercent, 0 < memoryPercent && memoryPercent <= 1);

        return b.maximumWeight((long) (Runtime.getRuntime().maxMemory() * memoryPercent))
                .weigher((k, v) -> entryWeigh);
    }

    public static Caffeine<Object, Object> slidingBuilder(long expireSeconds) {
        return rootBuilder()
                .expireAfterAccess(expireSeconds, TimeUnit.SECONDS);
    }

    public static Caffeine<Object, Object> absoluteBuilder(long expireSeconds) {
        return rootBuilder()
                .expireAfterWrite(expireSeconds, TimeUnit.SECONDS);
    }

    static Caffeine<Object, Object> rootBuilder() {
        return Caffeine.newBuilder().executor(Tasks.pool())
                .scheduler(Scheduler.forScheduledExecutorService(Tasks.scheduler()));
    }

    final com.github.benmanes.caffeine.cache.Cache<TK, TV> cache;
    final Policy.VarExpiration<TK, TV> expireVariably;

    public MemoryCache() {
        this(b -> b.maximumSize(SystemPropertyUtil.getInt(Constants.CACHE_DEFAULT_MAX_SIZE, 10000)));
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
