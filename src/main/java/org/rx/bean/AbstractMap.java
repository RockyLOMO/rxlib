package org.rx.bean;

import org.rx.core.NQuery;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

public interface AbstractMap<K, V> extends Map<K, V> {
//    @Override
//    public int size() {
//        return 0;
//    }

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

//    @Override
//    public V get(Object key) {
//        return null;
//    }
//
//    @Override
//    public V put(K key, V value) {
//        return null;
//    }

//    @Override
//    public V remove(Object key) {
//        return null;
//    }

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
        return NQuery.of(entrySet()).select(Entry::getKey).toSet();
    }

    @Override
    default Collection<V> values() {
        return NQuery.of(entrySet()).select(Entry::getValue).toList();
    }

    @Override
    default Set<Entry<K, V>> entrySet() {
        throw new UnsupportedOperationException();
    }
}
