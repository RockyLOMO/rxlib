package org.rx.redis;

import com.google.common.util.concurrent.RateLimiter;
import org.redisson.api.RLock;
import org.rx.core.Cache;
import org.rx.core.Strings;
import org.rx.core.Sys;
import org.rx.core.cache.MemoryCache;
import org.rx.util.Lazy;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class RedisUtil {
    public static Lock wrapLock(RLock rLock) {
        return Sys.fallbackProxy(Lock.class, rLock, new Lazy<>(ReentrantLock::new), e -> {
            if (Strings.hashEquals(e.getMethod().getName(), "unlock")) {
                return null;
            }
            throw e;
        });
    }

    public static <TK, TV> Cache<TK, TV> wrapCache(RedisCache<TK, TV> rCache) {
        return Sys.fallbackProxy(Cache.class, rCache, new Lazy<>(() -> Cache.getInstance(MemoryCache.class)));
    }

    public static RateLimiterAdapter wrapRateLimiter(RedisRateLimiter rRateLimiter) {
        return Sys.fallbackProxy(RateLimiterAdapter.class, rRateLimiter, new Lazy<>(() -> {
            RateLimiter limiter = RateLimiter.create(rRateLimiter.getPermitsPerSecond());
            return () -> limiter.tryAcquire();
        }));
    }
}
