package org.rx.spring;

import lombok.extern.slf4j.Slf4j;
import org.rx.core.*;
import org.rx.exception.InvalidException;
import org.rx.redis.RateLimiterAdapter;
import org.rx.redis.RedisCache;
import org.rx.redis.RedisRateLimiter;
import org.rx.redis.RedisUtil;
import org.rx.util.Servlets;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.rx.core.Extends.ifNull;

@Configuration
@Slf4j
public class BeanRegister {
    public static final String REDIS_PROP_NAME = "app.redisUrl";

    @Bean
    @ConditionalOnProperty(name = REDIS_PROP_NAME)
    public <TK, TV> RedisCache<TK, TV> redisCache(MiddlewareConfig conf) {
        if (Strings.isEmpty(conf.getRedisUrl())) {
            throw new InvalidException("app.redisUrl is null");
        }

        RedisCache<TK, TV> cache = new RedisCache<>(conf.getRedisUrl());
        IOC.register(Cache.class, RedisUtil.wrapCache(cache));
        log.info("register RedisCache ok");
        return cache;
    }

    @Bean
    @ConditionalOnProperty(name = REDIS_PROP_NAME)
    public RateLimiterAdapter httpRateLimiterAdapter(RedisCache<?, ?> rCache, MiddlewareConfig conf) {
        String[] acquireWhiteList = conf.getLimiterWhiteList();
        return () -> {
            String clientIp = ifNull(Servlets.requestIp(), "ALL");

            if (!Arrays.isEmpty(acquireWhiteList)
                    && Linq.from(acquireWhiteList).any(p -> Strings.startsWith(clientIp, p))) {
                return true;
            }

            String rk = "RateLimiter:" + clientIp;
            RateLimiterAdapter adapter = IOC.<String, RateLimiterAdapter>weakMap(true)
                    .computeIfAbsent(rk, k -> RedisUtil.wrapRateLimiter(new RedisRateLimiter(rCache, k, conf.getLimiterPermits())));
            return adapter.tryAcquire();
        };
    }
}
