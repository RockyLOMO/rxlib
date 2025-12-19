package org.rx.core.cache;

import com.github.benmanes.caffeine.cache.*;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.checkerframework.checker.index.qual.NonNegative;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.rx.core.Cache;
import org.rx.core.*;
import org.rx.util.function.BiAction;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.rx.core.Extends.as;
import static org.rx.core.Extends.require;

//@Slf4j
public class MemoryCache<TK, TV> implements Cache<TK, TV> {
    @RequiredArgsConstructor
    static class CaffeineExpiry implements Expiry<Object, Object> {
        static final long DEFAULT_SLIDING_NANOS = TimeUnit.SECONDS.toNanos(RxConfig.INSTANCE.getCache().getSlidingSeconds());
        final Map<Object, CachePolicy> policyMap;

        long computeNanos(Object key, Object value, long currentDuration) {
            long ttlNanos;
            CachePolicy policy = policyMap.get(key);
            if (policy == null) {
                policy = as(value, CachePolicy.class);
            }
            if (policy != null) {
//                log.debug("computeNanos key={} policy={} currentDuration={}", key, policy, currentDuration);
                //absolute 或 sliding 在policy.ttl()内部已处理
                ttlNanos = policy.isSliding() || currentDuration == -1 ? TimeUnit.MILLISECONDS.toNanos(policy.ttl()) : currentDuration;
            } else {
//                log.debug("computeNanos key={} currentDuration={}", key, currentDuration);
                //absolute
//                return currentDuration != -1 ? currentDuration : DEFAULT_SLIDING_NANOS;
                //sliding
                ttlNanos = DEFAULT_SLIDING_NANOS;
            }
//            log.debug("computeNanos key={} result={}", key, ttlNanos);
            return ttlNanos;
        }

        //currentTime = System.nanoTime()
        @Override
        public long expireAfterCreate(@NonNull Object key, @NonNull Object value, long currentTime) {
//        System.out.println("expireAfterCreate[-1]: " + key + "=" + value);
            return computeNanos(key, value, -1);
        }

        @Override
        public long expireAfterUpdate(@NonNull Object key, @NonNull Object value, long currentTime, @NonNegative long currentDuration) {
//        System.out.println("expireAfterUpdate[" + currentDuration + "]: " + key + "=" + value);
            return computeNanos(key, value, currentDuration);
        }

        @Override
        public long expireAfterRead(@NonNull Object key, @NonNull Object value, long currentTime, @NonNegative long currentDuration) {
//        System.out.println("expireAfterRead[" + currentDuration + "]: " + key + "=" + value);
            return computeNanos(key, value, currentDuration);
        }
    }

    static {
        IOC.register(MemoryCache.class, new MemoryCache<>());
    }

    public static <TK, TV> Caffeine<TK, TV> weightBuilder(Caffeine<TK, TV> b, float memoryPercent, int entryBytes) {
        require(memoryPercent, 0 < memoryPercent && memoryPercent <= 1);

        return weightBuilder(b, (long) (Runtime.getRuntime().maxMemory() * memoryPercent), entryBytes);
    }

    public static <TK, TV> Caffeine<TK, TV> weightBuilder(Caffeine<TK, TV> b, long maxBytes, int entryBytes) {
        return b.maximumWeight(maxBytes).weigher((k, v) -> entryBytes);
    }

    static <TK, TV> Caffeine<TK, TV> rootBuilder() {
        return (Caffeine<TK, TV>) Caffeine.newBuilder().executor(Tasks.executor()).scheduler(Scheduler.forScheduledExecutorService(Tasks.timer()));
    }

    final com.github.benmanes.caffeine.cache.Cache<TK, TV> cache;
    final Map<Object, CachePolicy> policyMap = new ConcurrentHashMap<>();
    final Policy.VarExpiration<TK, TV> expireVariably;

    public MemoryCache() {
        this(b -> b.maximumSize(RxConfig.INSTANCE.getCache().getMaxItemSize()), null);
    }

    @SneakyThrows
    public MemoryCache(BiAction<Caffeine<TK, TV>> onBuild, RemovalListener<TK, TV> extraRemovalListener) {
        Caffeine<TK, TV> builder = rootBuilder();
        if (onBuild != null) {
            onBuild.invoke(builder);
        }
        cache = builder.expireAfter(new CaffeineExpiry(policyMap))
                .removalListener((key, value, cause) -> {
//                    log.info("policyMap remove {} {}", key, cause);
                    if (cause != RemovalCause.REPLACED) {
                        policyMap.remove(key);
                    }
                    if (extraRemovalListener != null) {
                        extraRemovalListener.onRemoval(key, value, cause);
                    }
                }).build();
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
        return cache.getIfPresent((TK) key) != null;
    }

    @Override
    public boolean containsValue(Object value) {
        return cache.asMap().containsValue(value);
    }

    @Override
    public TV get(Object key) {
        return cache.getIfPresent((TK) key);
    }

    @Override
    public TV put(TK key, TV value, CachePolicy policy) {
        if (policy != null) {
            policyMap.put(key, policy);
        }
//        TV old = cache.getIfPresent(key);
//        cache.put(key, value);
//        return old;
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
