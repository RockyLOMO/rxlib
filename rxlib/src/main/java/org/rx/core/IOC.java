package org.rx.core;

import lombok.NonNull;
import lombok.SneakyThrows;
import org.apache.commons.lang3.ClassUtils;
import org.rx.bean.WeakIdentityMap;
import org.rx.exception.InvalidException;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import static org.rx.core.Constants.NON_UNCHECKED;

@SuppressWarnings(NON_UNCHECKED)
public final class IOC {
    static final Map<Class<?>, Object> container = new ConcurrentHashMap<>(8);
    static final Map WEAK_MAP = Collections.synchronizedMap(new WeakHashMap<>());
    //不要放值类型
    static final Map WEAK_IDENTITY_MAP = new WeakIdentityMap<>();

    public static <T> T get(Class<T> type, Class<? extends T> defType) {
        T bean = innerGet(type);
        if (bean == null) {
            return get(defType);
        }
        return bean;
    }

    public static <T> T get(Class<T> type) {
        T bean = innerGet(type);
        if (bean == null) {
            throw new InvalidException("Bean {} not registered", type.getName());
        }
        return bean;
    }

    @SneakyThrows
    static synchronized <T> T innerGet(Class<T> type) {
        T bean = (T) container.get(type);
        if (bean == null) {
            Class.forName(type.getName());
            bean = (T) container.get(type);
        }
        return bean;
    }

    public static <T> void register(@NonNull Class<T> type, @NonNull T bean) {
        List<Class<?>> types = ClassUtils.getAllSuperclasses(type);
        types.remove(Object.class);
        for (Class<?> t : types) {
            container.putIfAbsent(t, bean);
        }
        container.put(type, bean);
    }

    public static <T> void unregister(Class<T> type) {
        container.remove(type);
    }

    public static <K, V> Map<K, V> weakMap(boolean identity) {
        return identity ? WEAK_IDENTITY_MAP : WEAK_MAP;
    }

    static <K, V> Map<K, V> weakMap(Object ref, boolean identity) {
        return (Map<K, V>) (identity ? WEAK_IDENTITY_MAP : WEAK_MAP).computeIfAbsent(ref, k -> new ConcurrentHashMap<>(4));
    }
}
