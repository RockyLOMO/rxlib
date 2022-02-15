package org.rx.core.cache;

import com.github.benmanes.caffeine.cache.Expiry;
import lombok.RequiredArgsConstructor;
import org.checkerframework.checker.index.qual.NonNegative;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.rx.bean.DateTime;
import org.rx.core.CachePolicy;
import org.rx.core.RxConfig;

import java.util.concurrent.TimeUnit;

import static org.rx.core.Extends.eq;

@RequiredArgsConstructor
class CaffeineExpiry implements Expiry<Object, Object> {
    static final long DEFAULT_SLIDING_NANOS = TimeUnit.SECONDS.toNanos(RxConfig.INSTANCE.getCache().getSlidingSeconds());

    static long computeNanos(Object value, long currentDuration) {
        CachePolicy policy;
        if (value instanceof CachePolicy) {
            policy = (CachePolicy) value;
            DateTime absoluteExpiration = policy.getAbsoluteExpiration();
            if (!eq(absoluteExpiration, CachePolicy.NON_EXPIRE.getAbsoluteExpiration())) {
                long millis = absoluteExpiration.getTime() - System.currentTimeMillis();
                return TimeUnit.MILLISECONDS.toNanos(millis);
            }
            long slidingExpiration = policy.getSlidingExpiration();
            if (slidingExpiration != CachePolicy.NON_EXPIRE.getSlidingExpiration()) {
                return TimeUnit.MILLISECONDS.toNanos(slidingExpiration);
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
