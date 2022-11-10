package org.rx.bean;

import org.rx.core.Linq;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

public interface AbstractMap<K, V> extends Map<K, V> {
    @Override
    default boolean isEmpty() {
        return size() == 0;
    }

    @Override
    default boolean containsKey(Object key) {
        return get(key) != null;
    }

    @Override
    default boolean containsValue(Object value) {
        return values().contains(value);
    }

    @Override
    default void putAll(Map<? extends K, ? extends V> m) {
        for (Entry<? extends K, ? extends V> entry : m.entrySet()) {
            put(entry.getKey(), entry.getValue());
        }
    }

    @Override
    default void clear() {
        for (K k : keySet()) {
            remove(k);
        }
    }

    @Override
    default Set<K> keySet() {
        return Linq.from(entrySet()).select(Entry::getKey).toSet();
    }

    @Override
    default Collection<V> values() {
        return Linq.from(entrySet()).select(Entry::getValue).toList();
    }

    @Override
    default Set<Entry<K, V>> entrySet() {
        throw new UnsupportedOperationException();
    }
}
