package org.rx.core.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Scheduler;
import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import lombok.NoArgsConstructor;
import org.rx.bean.Tuple;
import org.rx.core.*;
import org.rx.util.function.BiAction;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.rx.core.App.quietly;
import static org.rx.core.App.require;

@NoArgsConstructor
public class MemoryCache<TK, TV> implements Cache<TK, TV> {
    static final int DEFAULT_Insertions = 9999999;  //1.1M
    public static final MemoryCache DEFAULT = new MemoryCache<>();

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

    final Map<CacheExpiration, Tuple<com.github.benmanes.caffeine.cache.Cache<TK, TV>, BloomFilter<CharSequence>>> cacheMap = new ConcurrentHashMap<>();
    BiAction<Caffeine<Object, Object>> onBuild;

    public MemoryCache(BiAction<Caffeine<Object, Object>> onBuild) {
        this.onBuild = onBuild;
    }

    String routeKey(TK key) {
        return key.getClass().hashCode() + ":" + key.hashCode();
    }

    com.github.benmanes.caffeine.cache.Cache<TK, TV> cache(TK key) {
        return NQuery.of(cacheMap.values()).where(p -> p.right.mightContain(routeKey(key))).select(p -> p.left).firstOrDefault(() -> cache(null, CacheExpiration.NON_EXPIRE));
    }

    com.github.benmanes.caffeine.cache.Cache<TK, TV> cache(TK key, CacheExpiration expiration) {
        Tuple<com.github.benmanes.caffeine.cache.Cache<TK, TV>, BloomFilter<CharSequence>> tuple = cacheMap.computeIfAbsent(expiration, k -> {
            App.log("MemoryCache create by {}", expiration);
            Caffeine<Object, Object> b;
            if (k.getSlidingExpiration() >= 0) {
                b = slidingBuilder(k.getSlidingExpiration());
            } else if (k.getAbsoluteTTL() > 0) {
                b = absoluteBuilder(k.getAbsoluteTTL());
            } else {
                b = rootBuilder();
            }
            if (onBuild != null) {
                quietly(() -> onBuild.invoke(b));
            }
            return Tuple.of(b.softValues().build(), BloomFilter.create(Funnels.stringFunnel(StandardCharsets.UTF_8), DEFAULT_Insertions));
        });
        if (key != null) {
            tuple.right.put(routeKey(key));
        }
        return tuple.left;
    }

    NQuery<com.github.benmanes.caffeine.cache.Cache<TK, TV>> caches() {
        return NQuery.of(cacheMap.values()).select(p -> p.left);
    }

    @Override
    public int size() {
        return (int) caches().sum(cache -> cache.estimatedSize());
    }

    @Override
    public boolean containsKey(Object key) {
//        return caches().any(cache -> cache.getIfPresent(key) != null);
        return cache((TK) key).getIfPresent(key) != null;
    }

    @Override
    public boolean containsValue(Object value) {
        return caches().any(cache -> cache.asMap().containsValue(value));
    }

    @Override
    public TV get(Object key) {
//        for (com.github.benmanes.caffeine.cache.Cache<TK, TV> cache : caches()) {
//            TV val = cache.getIfPresent(key);
//            if (val != null) {
//                return val;
//            }
//        }
//        return null;
        return cache((TK) key).getIfPresent(key);
    }

    @Override
    public TV put(TK key, TV value, CacheExpiration expiration) {
        if (expiration == null) {
            expiration = CacheExpiration.NON_EXPIRE;
        }

        return cache(key, expiration).asMap().put(key, value);
    }

    @Override
    public void putAll(Map<? extends TK, ? extends TV> m) {
        for (Entry<? extends TK, ? extends TV> entry : m.entrySet()) {
            cache(entry.getKey()).put(entry.getKey(), entry.getValue());
        }
    }

    @Override
    public TV putIfAbsent(TK key, TV value) {
        return cache(key).asMap().putIfAbsent(key, value);
    }

    @Override
    public boolean replace(TK key, TV oldValue, TV newValue) {
        return cache(key).asMap().replace(key, oldValue, newValue);
    }

    @Override
    public TV replace(TK key, TV value) {
        return cache(key).asMap().replace(key, value);
    }

    @Override
    public TV remove(Object key) {
        return cache((TK) key).asMap().remove(key);
    }

    @Override
    public boolean remove(Object key, Object value) {
        return cache((TK) key).asMap().remove(key, value);
    }

    @Override
    public void clear() {
        for (com.github.benmanes.caffeine.cache.Cache<TK, TV> cache : caches()) {
            cache.invalidateAll();
        }
    }

    @Override
    public Set<TK> keySet() {
        return caches().selectMany(cache -> cache.asMap().keySet()).toSet();
    }

    @Override
    public Collection<TV> values() {
        return caches().selectMany(cache -> cache.asMap().values()).toSet();
    }

    @Override
    public Set<Entry<TK, TV>> entrySet() {
        return caches().selectMany(cache -> cache.asMap().entrySet()).toSet();
    }
}
