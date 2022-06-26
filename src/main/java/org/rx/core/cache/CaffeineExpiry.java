package org.rx.core.cache;

import com.github.benmanes.caffeine.cache.Expiry;
import lombok.RequiredArgsConstructor;
import org.checkerframework.checker.index.qual.NonNegative;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.rx.core.CachePolicy;
import org.rx.core.RxConfig;

import java.util.concurrent.TimeUnit;

@RequiredArgsConstructor
class CaffeineExpiry implements Expiry<Object, Object> {
    static final long DEFAULT_SLIDING_NANOS = TimeUnit.SECONDS.toNanos(RxConfig.INSTANCE.getCache().getSlidingSeconds());

    static long computeNanos(Object value, long currentDuration) {
        CachePolicy policy;
        if (value instanceof CachePolicy) {
            policy = (CachePolicy) value;
            return TimeUnit.MILLISECONDS.toNanos(policy.ttl());
        }
        //absolute
//                return currentDuration != -1 ? currentDuration : DEFAULT_SLIDING_NANOS;
        //sliding
        return DEFAULT_SLIDING_NANOS;
//        return TimeUnit.SECONDS.toNanos(10);
    }

    @Override
    public long expireAfterCreate(@NonNull Object key, @NonNull Object value, long currentTime) {
        return computeNanos(value, -1);
    }

    @Override
    public long expireAfterUpdate(@NonNull Object key, @NonNull Object value, long currentTime, @NonNegative long currentDuration) {
        return computeNanos(value, currentDuration);
    }

    @Override
    public long expireAfterRead(@NonNull Object key, @NonNull Object value, long currentTime, @NonNegative long currentDuration) {
        return computeNanos(value, currentDuration);
    }
}
