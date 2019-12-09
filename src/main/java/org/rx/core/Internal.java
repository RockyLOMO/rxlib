package org.rx.core;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.util.function.BiFunc;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.TimeUnit;

import static org.rx.core.Contract.*;
import static org.rx.core.Contract.require;

class Internal {
    @Slf4j
    static class WeakCache<TK, TV> implements MemoryCache<TK, TV> {
        private final Map<TK, TV> container = Collections.synchronizedMap(new WeakHashMap<>());  //ReferenceQueue、ConcurrentMap<TK, Reference<TV>> 不准

        @Override
        public int size() {
            return container.size();
        }

        @Override
        public Set<TK> keySet() {
            return container.keySet();
        }

        @Override
        public void add(TK key, TV val) {
            container.put(key, val);
        }

        @Override
        public void remove(TK key, boolean destroy) {
            TV val = container.remove(key);
            if (destroy) {
                tryClose(val);
            }
        }

        @Override
        public void clear() {
            container.clear();
        }

        @Override
        public TV get(TK key) {
            TV val = container.get(key);
            if (val == null) {
                log.debug("get key {} is gc", key);
                remove(key);
                return null;
            }
            return val;
        }

        @SneakyThrows
        @Override
        public TV get(TK key, BiFunc<TK, TV> supplier) {
            require(supplier);

            TV v = get(key);
            if (v == null) {
                add(key, v = supplier.invoke(key));
            }
            return v;
        }
    }

    @RequiredArgsConstructor
    static class GoogleCache<TK, TV> implements MemoryCache<TK, TV> {
        private final Cache<TK, TV> cache;

        @Override
        public int size() {
            return (int) cache.size();
        }

        @Override
        public Set<TK> keySet() {
            return cache.asMap().keySet();
        }

        @Override
        public void add(TK key, TV val) {
            cache.put(key, val);
        }

        @Override
        public void remove(TK key, boolean destroy) {
            TV val = cache.getIfPresent(key);
            if (val == null) {
                return;
            }

            cache.invalidate(key);
            if (destroy) {
                tryClose(val);
            }
        }

        @Override
        public void clear() {
            cache.invalidateAll();
        }

        @Override
        public TV get(TK key) {
            return cache.getIfPresent(key);
        }

        @SneakyThrows
        @Override
        public TV get(TK key, BiFunc<TK, TV> supplier) {
            return cache.get(key, () -> {
                try {
                    return supplier.invoke(key);
                } catch (Throwable e) {
                    throw SystemException.wrap(e);
                }
            });
        }
    }

    static final WeakCache WeakCache = new WeakCache<>();
    static final Lazy<GoogleCache> LazyCache = new Lazy<>(() -> new GoogleCache<>(CacheBuilder.newBuilder().maximumSize(MaxInt).expireAfterAccess(App.Config.getCacheLiveMinutes(), TimeUnit.MINUTES).build()));
}
