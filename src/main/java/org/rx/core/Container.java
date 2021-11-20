package org.rx.core;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import org.rx.core.cache.MemoryCache;
import org.rx.exception.InvalidException;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class Container {
    public static final Container INSTANCE = new Container();
    //ReferenceQueue、ConcurrentMap<TK, Reference<TV>> 不准, soft 内存不够时才回收
    static final Map WEAK_MAP = Collections.synchronizedMap(new WeakHashMap<>());

    static {
        INSTANCE.register(Cache.MAIN_CACHE, INSTANCE.get(MemoryCache.class));
    }

    //不要放值类型
    public static <K, V> Map<K, V> weakMap() {
        return WEAK_MAP;
    }

    final Map<Class<?>, Object> holder = new ConcurrentHashMap<>(8);

    public <T> T get(Class<T> type) {
        T instance = (T) holder.get(type);
        if (instance == null) {
            throw new InvalidException("Bean %s not registered", type.getName());
        }
        return instance;
    }

    public <T> void register(Class<T> type, @NonNull T instance) {
        holder.put(type, instance);
    }

    public <T> void unregister(Class<T> type) {
        holder.remove(type);
    }
}
