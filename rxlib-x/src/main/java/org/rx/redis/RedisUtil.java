package org.rx.redis;

import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
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

    public static <TK, TV> Cache<TK, TV> wrapCache(String redisUrl) {
        return Sys.fallbackProxy(Cache.class, new RedisCache<>(redisUrl), new Lazy<>(() -> Cache.getInstance(MemoryCache.class)));
    }
}
