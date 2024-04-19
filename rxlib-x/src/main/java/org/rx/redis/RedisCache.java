package org.rx.redis;

import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.codec.SerializationCodec;
import org.redisson.config.Config;
import org.rx.bean.BiTuple;
import org.rx.codec.CodecUtil;
import org.rx.core.*;
import org.rx.util.function.BiFunc;

import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.rx.core.Extends.quietly;

@Slf4j
public class RedisCache<TK, TV> implements Cache<TK, TV> {
    public static RedissonClient create(String redisUrl) {
        return create(redisUrl, false);
    }

    public static RedissonClient create(@NonNull String redisUrl, boolean jdkCodec) {
        BiTuple<String, Integer, String> resolve = resolve(redisUrl);
        log.info("RedissonClient {} -> {}", redisUrl, resolve);
        Config config = new Config();
        config.setExecutor(Tasks.executor());
        if (jdkCodec) {
            config.setCodec(new SerializationCodec());
        }
        int minPoolSize = 2;
        int maxPoolSize = Math.max(minPoolSize, RxConfig.INSTANCE.getNet().getPoolMaxSize());
        config.useSingleServer().setKeepAlive(true).setTcpNoDelay(true)
                .setConnectionMinimumIdleSize(minPoolSize).setConnectionPoolSize(maxPoolSize)
                .setSubscriptionConnectionMinimumIdleSize(minPoolSize).setSubscriptionConnectionPoolSize(maxPoolSize)
                .setAddress(String.format("redis://%s", resolve.left)).setDatabase(resolve.middle).setPassword(resolve.right);
        return Redisson.create(config);
    }

    private static BiTuple<String, Integer, String> resolve(String redisUrl) {
        String pwd = null;
        int database = 0, i;
        if ((i = redisUrl.lastIndexOf("/")) != -1) {
            database = Integer.parseInt(redisUrl.substring(i + 1));
            redisUrl = redisUrl.substring(0, i);
        }
        if ((i = redisUrl.lastIndexOf("@")) != -1) {
            pwd = redisUrl.substring(0, i);
            redisUrl = redisUrl.substring(i + 1);
        }
        return BiTuple.of(redisUrl, database, pwd);
    }

    static final String BASE64_KEY_PREFIX = "B:";
    @Getter
    final RedissonClient client;
    @Getter
    @Setter
    int entrySetLimit = 1000;

    @Override
    public int size() {
        int is = (int) client.getKeys().count();
        if (is < 0) {
            is = Integer.MAX_VALUE;
        }
        return is;
    }

    public RedisCache(String redisUrl) {
        client = create(redisUrl);
    }

    @Override
    public Set<Entry<TK, TV>> entrySet() {
        Set<Entry<TK, TV>> entrySet = new HashSet<>();
        for (String rKey : client.getKeys().getKeysWithLimit(entrySetLimit)) {
            TV val = quietly(() -> client.<TV>getBucket(rKey).get());
            if (val == null) {
                continue;
            }
            entrySet.add(new AbstractMap.SimpleEntry<>(transferKey(rKey), val));
        }
        return entrySet;
    }

    protected String transferKey(@NonNull TK k) {
        if (k instanceof String) {
            return k.toString();
        }
        return BASE64_KEY_PREFIX + CodecUtil.serializeToBase64(k);
    }

    protected TK transferKey(@NonNull String k) {
        if (k.startsWith(BASE64_KEY_PREFIX)) {
            return CodecUtil.deserializeFromBase64(k.substring(BASE64_KEY_PREFIX.length()));
        }
        return (TK) k;
    }

    @Override
    public TV put(TK k, @NonNull TV v, CachePolicy policy) {
        long expireMillis = policy != null ? policy.ttl() : -1;
        RBucket<TV> bucket = client.getBucket(transferKey(k));
        return expireMillis < 1 ? bucket.getAndSet(v) : bucket.getAndSet(v, expireMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public TV remove(Object k) {
        return client.<TV>getBucket(transferKey((TK) k)).getAndDelete();
    }

    @Override
    public void clear() {
        client.getKeys().flushdb();
    }

    @Override
    public TV get(Object k) {
        return client.<TV>getBucket(transferKey((TK) k)).get();
    }

    @Override
    public TV get(TK k, @NonNull BiFunc<TK, TV> loadingFunc, CachePolicy policy) {
        RBucket<TV> bucket = client.getBucket(transferKey(k));
        TV v = bucket.get();
        if (v != null) {
            if (policy != null && policy.slidingRenew()) {
                long expireMillis = policy.ttl(false);
                if (expireMillis > 0) {
                    bucket.expireAsync(expireMillis, TimeUnit.MILLISECONDS);
                }
            }
            return v;
        }
        put(k, v = loadingFunc.apply(k), policy);
        return v;
    }
}
