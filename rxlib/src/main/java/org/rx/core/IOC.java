package org.rx.core;

import lombok.NonNull;
import lombok.SneakyThrows;
import org.apache.commons.collections4.map.AbstractReferenceMap;
import org.apache.commons.collections4.map.ReferenceIdentityMap;
import org.apache.commons.collections4.map.ReferenceMap;
import org.rx.bean.ConcurrentWeakMap;
import org.rx.exception.InvalidException;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.rx.core.Constants.NON_UNCHECKED;

@SuppressWarnings(NON_UNCHECKED)
public final class IOC {
    static final Map<Class<?>, Object> container = new ConcurrentHashMap<>(8);
    //    static final Map WEAK_KEY_MAP = Collections.synchronizedMap(new WeakHashMap<>());
    static final Map WEAK_KEY_MAP = new ConcurrentWeakMap<>(false);
    static Map weakValMap, wKeyIdentityMap, wValIdentityMap;

    public static <T> boolean isInit(Class<T> type) {
        return container.containsKey(type);
    }

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
        List<Class<?>> types = new ArrayList<>();
        types.add(type);
        Class<?> superclass = type.getSuperclass();
        if (superclass != null) {
            types.add(superclass);
        }
        for (Class<?> i : type.getInterfaces()) {
            types.add(i);
        }
        for (Class<?> t : types) {
            container.putIfAbsent(t, bean);
        }
        container.put(type, bean);
    }

    public static <T> void unregister(Class<T> type) {
        container.remove(type);
    }

    public static <K, V> Map<K, V> weakMap(boolean weakValue) {
        if (!weakValue) {
            return WEAK_KEY_MAP;
        }
        synchronized (WEAK_KEY_MAP) {
            if (weakValMap == null) {
                weakValMap = Collections.synchronizedMap(new ReferenceMap<>(AbstractReferenceMap.ReferenceStrength.HARD, AbstractReferenceMap.ReferenceStrength.WEAK));
            }
            return weakValMap;
        }
    }

    static <K, V> Map<K, V> weakMap(Object ref, boolean weakValue) {
        return (Map<K, V>) weakMap(weakValue).computeIfAbsent(ref, k -> new ConcurrentHashMap<>(4));
    }

    public synchronized static <K, V> Map<K, V> weakIdentityMap(boolean weakValue) {
        if (weakValue) {
            if (wValIdentityMap == null) {
                wValIdentityMap = Collections.synchronizedMap(new ReferenceIdentityMap<>(AbstractReferenceMap.ReferenceStrength.HARD, AbstractReferenceMap.ReferenceStrength.WEAK));
            }
            return wValIdentityMap;
        }
        if (wKeyIdentityMap == null) {
            wKeyIdentityMap = new ConcurrentWeakMap<>(true);
        }
        return wKeyIdentityMap;
    }
}
