package org.rx.core.cache;

import lombok.Getter;
import org.rx.core.CacheKind;
import org.rx.core.InvalidOperationException;
import org.rx.core.MemoryCache;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.rx.core.Contract.NON_WARNING;
import static org.rx.core.Contract.require;

public final class CacheFactory {
    @Getter
    private static final CacheFactory instance = new CacheFactory();

    private final Map<String, MemoryCache> registered = new ConcurrentHashMap<>(3);

    private CacheFactory() {
        register(CacheKind.ThreadCache.name(), new ThreadCache<>());
        register(CacheKind.WeakCache.name(), new WeakCache<>());
        register(CacheKind.LruCache.name(), new LocalCache<>());
    }

    public <T extends MemoryCache> void register(String name, MemoryCache cache) {
        require(name, cache);

        registered.put(name, cache);
    }

    public void unregister(String name) {
        registered.remove(name);
    }

    @SuppressWarnings(NON_WARNING)
    public <TK, TV> MemoryCache<TK, TV> get(String name) {
        MemoryCache<TK, TV> cache = registered.get(name);
        if (cache == null) {
            throw new InvalidOperationException("MemoryCache %s not registered", name);
        }
        return cache;
    }
}
