package org.rx.core.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Scheduler;
import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import io.netty.util.internal.SystemPropertyUtil;
import org.rx.bean.Tuple;
import org.rx.core.*;
import org.rx.util.function.BiAction;
import org.rx.util.function.BiFunc;

import java.util.*;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.rx.core.App.quietly;
import static org.rx.core.App.require;

@SuppressWarnings(Constants.NON_UNCHECKED)
public class MemoryCache<TK, TV> implements Cache<TK, TV> {
    public static class MultiExpireCache<TK, TV> extends MemoryCache<TK, TV> {
        static final int DEFAULT_INSERTIONS = 9999999;  //1.1M
        final Map<CacheExpiration, Tuple<com.github.benmanes.caffeine.cache.Cache<TK, TV>, BloomFilter<Integer>>> cacheMap = new ConcurrentHashMap<>();
        BiAction<Caffeine<Object, Object>> onBuild;

        public MultiExpireCache(BiAction<Caffeine<Object, Object>> onBuild) {
            this.onBuild = onBuild;
        }

        int routeKey(TK key) {
            if (key instanceof String) {
                return key.hashCode();
            }
            return Arrays.hashCode(new int[]{key.getClass().hashCode(), key.hashCode()});
//        int x = key.getClass().hashCode();
//        int y = key.hashCode();
//        return (((long) x) << 32) | (y & 0xffffffffL);
        }

        com.github.benmanes.caffeine.cache.Cache<TK, TV> cache(TK key) {
            for (Tuple<com.github.benmanes.caffeine.cache.Cache<TK, TV>, BloomFilter<Integer>> p : cacheMap.values()) {
                if (p.right.mightContain(routeKey(key))) {
                    return p.left;
                }
            }
            return cache;
        }

        com.github.benmanes.caffeine.cache.Cache<TK, TV> cache(TK key, CacheExpiration expiration) {
            if (CacheExpiration.NON_EXPIRE.equals(expiration)) {
                return cache;
            }

            Tuple<com.github.benmanes.caffeine.cache.Cache<TK, TV>, BloomFilter<Integer>> tuple = cacheMap.computeIfAbsent(expiration, k -> {
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
                return Tuple.of(b.build(), BloomFilter.create(Funnels.integerFunnel(), DEFAULT_INSERTIONS));
            });
            if (key != null) {
                tuple.right.put(routeKey(key));
            }
            return tuple.left;
        }

        NQuery<com.github.benmanes.caffeine.cache.Cache<TK, TV>> caches() {
            return NQuery.of(cacheMap.values()).select(p -> p.left).union(Collections.singletonList(cache));
        }

        @Override
        public int size() {
            return (int) caches().sum(cache -> cache.estimatedSize());
        }

        @Override
        public boolean containsKey(Object key) {
            return cache((TK) key).getIfPresent(key) != null;
        }

        @Override
        public boolean containsValue(Object value) {
            return caches().any(cache -> cache.asMap().containsValue(value));
        }

        @Override
        public TV get(Object key) {
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

    static {
        boolean multiExpire = SystemPropertyUtil.getBoolean(Constants.CACHE_MULTI_EXPIRATION, false);
        Container.register(MemoryCache.class, multiExpire ? new MultiExpireCache<>(null) : new MemoryCache<>());
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

    final com.github.benmanes.caffeine.cache.Cache<TK, TV> cache = slidingBuilder(SystemPropertyUtil.getInt(Constants.CACHE_DEFAULT_SLIDING_SECONDS, 60)).build();

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
    public TV get(TK key, BiFunc<TK, TV> loadingFunc, CacheExpiration expiration) {
        return cache.get(key, loadingFunc.toFunction());
    }

    @Override
    public TV put(TK key, TV value, CacheExpiration expiration) {
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
