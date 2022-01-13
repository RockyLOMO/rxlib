package org.rx.core.cache;

import com.github.benmanes.caffeine.cache.Expiry;
import io.netty.util.internal.SystemPropertyUtil;
import lombok.RequiredArgsConstructor;
import org.checkerframework.checker.index.qual.NonNegative;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.rx.bean.DateTime;
import org.rx.core.CachePolicy;
import org.rx.core.Constants;

import java.util.concurrent.TimeUnit;

@RequiredArgsConstructor
class CaffeineExpiry implements Expiry<Object, Object> {
    static final long DEFAULT_SLIDING_NANOS = TimeUnit.SECONDS.toNanos(SystemPropertyUtil.getInt(Constants.CACHE_DEFAULT_SLIDING_SECONDS, 60));

    static long computeNanos(Object value, long currentDuration) {
        CachePolicy policy;
        if (value instanceof CachePolicy) {
            policy = (CachePolicy) value;
            DateTime absoluteExpiration = policy.getAbsoluteExpiration();
            if (absoluteExpiration != CachePolicy.NON_EXPIRE.getAbsoluteExpiration()) {
                return TimeUnit.MICROSECONDS.toNanos(System.currentTimeMillis() - absoluteExpiration.getTime());
            }
            long slidingExpiration = policy.getSlidingExpiration();
            if (slidingExpiration != CachePolicy.NON_EXPIRE.getSlidingExpiration()) {
                return TimeUnit.MICROSECONDS.toNanos(slidingExpiration);
            }
        }
        //absolute
//                return currentDuration != -1 ? currentDuration : DEFAULT_SLIDING_NANOS;
        //sliding
        return DEFAULT_SLIDING_NANOS;
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
