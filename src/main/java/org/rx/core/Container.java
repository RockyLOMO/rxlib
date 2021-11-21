package org.rx.core;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import lombok.NonNull;
import lombok.SneakyThrows;
import org.rx.exception.InvalidException;
import org.rx.spring.SpringContext;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

public final class Container {
    static final Map<Class<?>, Object> holder = new ConcurrentHashMap<>(8);
    static final BloomFilter<Integer> spring = BloomFilter.create(Funnels.integerFunnel(), 100);
    //不要放值类型, ReferenceQueue、ConcurrentMap<TK, Reference<TV>> 不准, soft 内存不够时才回收
    static final Map WEAK_MAP = Collections.synchronizedMap(new WeakHashMap<>());

    @SneakyThrows
    public static <T> T getOrDefault(Class<T> type, Class<? extends T> defType) {
        Class.forName(type.getName());
        T instance = (T) holder.get(type);
        if (instance == null) {
            return get(defType);
        }
        return instance;
    }

    @SneakyThrows
    public static <T> T get(Class<T> type) {
        T instance;
        if (SpringContext.isInitiated() && spring.mightContain(type.hashCode())) {
            instance = SpringContext.getBean(type);
            if (instance != null) {
                return instance;
            }
        }

        Class.forName(type.getName());
        instance = (T) holder.get(type);
        if (instance == null) {
            throw new InvalidException("Bean %s not registered", type.getName());
        }
        return instance;
    }

    public static <T> void register(Class<T> type, @NonNull T instance) {
        register(type, instance, false);
    }

    public static <T> void register(Class<T> type, @NonNull T instance, boolean springPriority) {
        holder.put(type, instance);
        if (springPriority) {
            spring.put(type.hashCode());
        }
    }

    public static <T> void unregister(Class<T> type) {
        holder.remove(type);
    }

    public static <K, V> Map<K, V> weakMap() {
        return WEAK_MAP;
    }
}
