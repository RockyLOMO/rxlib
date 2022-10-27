package org.rx.core;

import lombok.NonNull;
import lombok.SneakyThrows;
import org.rx.exception.InvalidException;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import static org.rx.core.Constants.NON_UNCHECKED;

@SuppressWarnings(NON_UNCHECKED)
public final class Container {
    static final Map<Class<?>, Object> HOLDER = new ConcurrentHashMap<>(8);
    //不要放值类型, ReferenceQueue、ConcurrentMap<TK, Reference<TV>> 不准
    static final Map WEAK_MAP = Collections.synchronizedMap(new WeakHashMap<>());

    @SneakyThrows
    public static <T> T getOrDefault(Class<T> type, Class<? extends T> defType) {
        T instance = (T) HOLDER.get(type);
        if (instance == null) {
            Class.forName(type.getName());
            instance = (T) HOLDER.get(type);
            if (instance == null) {
                return get(defType);
            }
        }
        return instance;
    }

    @SneakyThrows
    public static <T> T get(Class<T> type) {
        T instance = (T) HOLDER.get(type);
        if (instance == null) {
            Class.forName(type.getName());
            instance = (T) HOLDER.get(type);
            if (instance == null) {
                throw new InvalidException("Bean {} not registered", type.getName());
            }
        }
        return instance;
    }

    public static <T> void register(@NonNull Class<T> type, @NonNull T instance) {
        HOLDER.put(type, instance);
    }

    public static <T> void unregister(Class<T> type) {
        HOLDER.remove(type);
    }

    static <K, V> Map<K, V> weakMap() {
        return WEAK_MAP;
    }
}