package org.rx.core;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.rx.exception.InvalidException;
import org.rx.util.function.Func;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import static org.rx.core.App.NON_WARNING;
import static org.rx.core.App.sneakyInvoke;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class Container {
    public static final Container INSTANCE = new Container();
    //ReferenceQueue、ConcurrentMap<TK, Reference<TV>> 不准, soft 内存不够时才会回收
    private static final Map WEAK_MAP = Collections.synchronizedMap(new WeakHashMap<>());

    public static <K, V> Map<K, V> weakMap() {
        return WEAK_MAP;
    }

    private final Map<String, Object> holder = new ConcurrentHashMap<>(8);

    public <T> T getOrRegister(Class<T> type) {
        return (T) holder.computeIfAbsent(type.getName(), k -> Reflects.newInstance(type));
    }

    public <T> T getOrRegister(String name, Func<T> func) {
        return (T) holder.computeIfAbsent(name, k -> sneakyInvoke(func));
    }

    public <T> T get(Class<T> type) {
        return get(type.getName());
    }

    @SuppressWarnings(NON_WARNING)
    public <T> T get(String name) {
        T instance = (T) holder.get(name);
        if (instance == null) {
            throw new InvalidException("%s not registered", name);
        }
        return instance;
    }

    public <T> void register(Class<T> type, T instance) {
        register(type.getName(), instance);
    }

    public <T> void register(String name, T instance) {
        if (instance == null) {
            throw new InvalidException("%s instance can not be null", name);
        }

        holder.put(name, instance);
    }

    public void unregister(String name) {
        holder.remove(name);
    }
}
