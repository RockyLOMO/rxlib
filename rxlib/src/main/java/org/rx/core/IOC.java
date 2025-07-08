package org.rx.core;

import lombok.NonNull;
import lombok.SneakyThrows;
import org.apache.commons.collections4.map.AbstractReferenceMap;
import org.apache.commons.collections4.map.ReferenceIdentityMap;
import org.apache.commons.collections4.map.ReferenceMap;
import org.rx.exception.InvalidException;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.rx.core.Constants.NON_UNCHECKED;
import static org.rx.core.Extends.tryClose;

@SuppressWarnings(NON_UNCHECKED)
public final class IOC {
    static final Map<Class<?>, Object> container = new ConcurrentHashMap<>(8);
    static final Map WEAK_KEY_MAP = Collections.synchronizedMap(new WeakHashMap<>());
    //    static final Map WEAK_KEY_MAP = Collections.synchronizedMap(new ReferenceMap<>(AbstractReferenceMap.ReferenceStrength.WEAK, AbstractReferenceMap.ReferenceStrength.HARD));
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
        tryClose(container.remove(type));
    }

    public static <K, V> Map<K, V> weakKeyMap() {
        return WEAK_KEY_MAP;
    }

    static <K, V> Map<K, V> weakMap(Object ref) {
        return (Map<K, V>) weakKeyMap().computeIfAbsent(ref, k -> new ConcurrentHashMap<>(4));
    }

    public static synchronized <K, V> Map<K, V> weakValueMap() {
        if (weakValMap == null) {
            weakValMap = Collections.synchronizedMap(new ReferenceMap<>(AbstractReferenceMap.ReferenceStrength.HARD, AbstractReferenceMap.ReferenceStrength.WEAK));
        }
        return weakValMap;
    }

    //不要放值类型
    public synchronized static <K, V> Map<K, V> weakKeyIdentityMap() {
        if (wKeyIdentityMap == null) {
            wKeyIdentityMap = Collections.synchronizedMap(new ReferenceIdentityMap<>(AbstractReferenceMap.ReferenceStrength.WEAK, AbstractReferenceMap.ReferenceStrength.HARD));
        }
        return wKeyIdentityMap;
    }

    public synchronized static <K, V> Map<K, V> weakValueIdentityMap() {
        if (wValIdentityMap == null) {
            wValIdentityMap = Collections.synchronizedMap(new ReferenceIdentityMap<>(AbstractReferenceMap.ReferenceStrength.HARD, AbstractReferenceMap.ReferenceStrength.WEAK));
        }
        return wValIdentityMap;
    }
}
