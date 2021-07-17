package org.rx.core.cache;

import io.netty.util.concurrent.FastThreadLocal;
import lombok.Getter;
import org.rx.core.Cache;
import org.rx.core.CacheExpirations;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

public final class ThreadCache<TK, TV> implements Cache<TK, TV> {
    @Getter
    static ThreadCache instance = new ThreadCache<>();
    //Java 11 HashMap.computeIfAbsent java.util.ConcurrentModificationException
    static final FastThreadLocal<Map> local = new FastThreadLocal<Map>() {
        @Override
        protected Map<?, ?> initialValue() {
            return new WeakHashMap<>(8);
        }
    };

    private ThreadCache() {
    }

    @Override
    public int size() {
        return local.get().size();
    }

    @Override
    public TV get(Object key) {
        return (TV) local.get().get(key);
    }

    @Override
    public TV put(TK key, TV value, CacheExpirations expiration) {
        return (TV) local.get().put(key, value);
    }

    @Override
    public TV remove(Object key) {
        return (TV) local.get().remove(key);
    }

    @Override
    public void clear() {
        local.remove();
    }

    @Override
    public Set<TK> keySet() {
        return local.get().keySet();
    }

    @Override
    public Collection<TV> values() {
        return local.get().values();
    }

    @Override
    public Set<Entry<TK, TV>> entrySet() {
        return local.get().entrySet();
    }
}
