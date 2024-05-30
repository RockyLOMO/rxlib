package org.rx.core.cache;

import com.github.benmanes.caffeine.cache.Expiry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.checkerframework.checker.index.qual.NonNegative;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.rx.core.CachePolicy;
import org.rx.core.IOC;
import org.rx.core.RxConfig;
import org.rx.core.Sys;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.rx.core.Extends.as;

//@Slf4j
@RequiredArgsConstructor
class CaffeineExpiry implements Expiry<Object, Object> {
    static final long DEFAULT_SLIDING_NANOS = TimeUnit.SECONDS.toNanos(RxConfig.INSTANCE.getCache().getSlidingSeconds());
    final Map<Object, CachePolicy> policyMap;

    long computeNanos(Object key, Object value, long currentDuration) {
        long ttlNanos;
        CachePolicy policy = policyMap.get(key);
        if (policy == null) {
            policy = as(value, CachePolicy.class);
        }
        if (policy != null) {
//            log.debug("computeNanos key={} policy={} currentDuration={}", key, policy, currentDuration);
            //absolute 或 sliding 在policy.ttl()内部已处理
            ttlNanos = policy.isSliding() || currentDuration == -1 ? TimeUnit.MILLISECONDS.toNanos(policy.ttl()) : currentDuration;
        } else {
            //absolute
//                return currentDuration != -1 ? currentDuration : DEFAULT_SLIDING_NANOS;
            //sliding
            ttlNanos = DEFAULT_SLIDING_NANOS;
        }
//        log.debug("computeNanos key={} result={}", key, ttlNanos);
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
