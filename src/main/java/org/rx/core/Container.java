package org.rx.core;

import lombok.NonNull;
import lombok.SneakyThrows;
import org.rx.exception.InvalidException;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

public final class Container {
    static final Map<Class<?>, Object> holder = new ConcurrentHashMap<>(8);
    //不要放值类型, ReferenceQueue、ConcurrentMap<TK, Reference<TV>> 不准, soft 内存不够时才回收
    static final Map WEAK_MAP = Collections.synchronizedMap(new WeakHashMap<>());

    public static <T> T getOrDefault(Class<T> type, Class<? extends T> defInstance) {
        T instance = (T) holder.get(type);
        if (instance == null) {
            return get(defInstance);
        }
        return instance;
    }

    @SneakyThrows
    public static <T> T get(Class<T> type) {
        Class.forName(type.getName());
        T instance = (T) holder.get(type);
        if (instance == null) {
            throw new InvalidException("Bean %s not registered", type.getName());
        }
        return instance;
    }

    public static <T> void register(Class<T> type, @NonNull T instance) {
        holder.put(type, instance);
    }

    public static <T> void unregister(Class<T> type) {
        holder.remove(type);
    }

    public static <K, V> Map<K, V> weakMap() {
        return WEAK_MAP;
    }
}
