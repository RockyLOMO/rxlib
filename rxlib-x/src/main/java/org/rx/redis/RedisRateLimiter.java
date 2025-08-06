package org.rx.redis;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateLimiterConfig;
import org.redisson.api.RateType;
import org.rx.core.Constants;
import org.rx.util.RateLimiterAdapter;

@Slf4j
public class RedisRateLimiter implements RateLimiterAdapter {
    final RedisCache<?, ?> rCache;
    final RRateLimiter limiter;

    public int getPermitsPerSecond() {
        return limiter.getConfig().getRate().intValue();
    }

    @Override
    public boolean tryAcquire() {
        return limiter.tryAcquire();
    }

    public RedisRateLimiter(@NonNull RedisCache<?, ?> rCache, String acquireKey) {
        this(rCache, acquireKey, Constants.CPU_THREADS);
    }

    public RedisRateLimiter(@NonNull RedisCache<?, ?> rCache, String acquireKey, int permitsPerSecond) {
        this.rCache = rCache;
        limiter = createLimiter(acquireKey, permitsPerSecond, 1);
    }

    RRateLimiter createLimiter(String key, long rate, long rateInterval) {
        RRateLimiter limiter = rCache.getClient().getRateLimiter(key);
        if (limiter.isExists()) {
            RateLimiterConfig config = limiter.getConfig();
            if (config.getRate() == rate && config.getRateInterval() == RateIntervalUnit.SECONDS.toMillis(rateInterval)) {
                return limiter;
            }
        }

        log.info("trySetRate start, {} {}", key, rate);
        int retry = 4;
        // 循环直到重新配置成功
        while (--retry > 0 && !limiter.trySetRate(RateType.OVERALL, rate, rateInterval, RateIntervalUnit.SECONDS)) {
            limiter.delete();
            limiter = rCache.getClient().getRateLimiter(key);
        }
        if (retry == 0) {
            log.warn("trySetRate fail, {} {}", key, rate);
        }
        return limiter;
    }
}
