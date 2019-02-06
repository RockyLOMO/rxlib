package org.rx.cache;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import lombok.SneakyThrows;
import org.rx.common.*;

import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.rx.common.Contract.require;

public class MemoryCache {
    private static Lazy<Cache<String, Object>> instance = new Lazy<>(() -> CacheBuilder.newBuilder()
            .expireAfterAccess(2, TimeUnit.MINUTES).build());

    public static Cache<String, Object> getInstance() {
        return instance.getValue();
    }

    @SneakyThrows
    public static Object getOrStore(String key, Function<String, Object> supplier) {
        require(key, supplier);

        return getInstance().get(App.cacheKey(key), () -> supplier.apply(key));
    }
}
