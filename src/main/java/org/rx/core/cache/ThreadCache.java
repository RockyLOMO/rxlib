package org.rx.core.cache;

import io.netty.util.concurrent.FastThreadLocal;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.rx.core.Cache;
import org.rx.core.CachePolicy;
import org.rx.core.Container;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import static org.rx.core.Constants.NON_UNCHECKED;

@SuppressWarnings(NON_UNCHECKED)
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ThreadCache<TK, TV> implements Cache<TK, TV> {
    //Java 11 HashMap.computeIfAbsent java.util.ConcurrentModificationException
    static final FastThreadLocal<Map> local = new FastThreadLocal<Map>() {
        @Override
        protected Map<?, ?> initialValue() {
            return new WeakHashMap<>(8);
        }
    };

    static {
        Container.register(ThreadCache.class, new ThreadCache<>());
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
    public TV put(TK key, TV value, CachePolicy policy) {
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
