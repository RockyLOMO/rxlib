package org.rx.core.cache;

import lombok.Getter;
import org.rx.core.InvalidOperationException;
import org.rx.core.Cache;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.rx.core.Contract.NON_WARNING;
import static org.rx.core.Contract.require;

public final class CacheFactory {
    @Getter
    private static final CacheFactory instance = new CacheFactory();

    private final Map<String, Cache> registered = new ConcurrentHashMap<>(3);

    private CacheFactory() {
        register(Cache.THREAD_CACHE, new ThreadCache<>());
        register(Cache.WEAK_CACHE, new WeakCache<>());
        register(Cache.LRU_CACHE, new LocalCache<>());
    }

    public <T extends Cache> void register(String name, Cache cache) {
        require(name, cache);

        registered.put(name, cache);
    }

    public void unregister(String name) {
        registered.remove(name);
    }

    @SuppressWarnings(NON_WARNING)
    public <TK, TV> Cache<TK, TV> get(String name) {
        Cache<TK, TV> cache = registered.get(name);
        if (cache == null) {
            throw new InvalidOperationException("MemoryCache %s not registered", name);
        }
        return cache;
    }
}
