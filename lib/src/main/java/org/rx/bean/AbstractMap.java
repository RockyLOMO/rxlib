package org.rx.bean;

import java.util.*;

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
        Iterator<Entry<K, V>> i = entrySet().iterator();
        if (value == null) {
            while (i.hasNext()) {
                Entry<K, V> e = i.next();
                if (e.getValue() == null) {
                    return true;
                }
            }
        } else {
            while (i.hasNext()) {
                Entry<K, V> e = i.next();
                if (value.equals(e.getValue())) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    default void putAll(Map<? extends K, ? extends V> m) {
        for (Map.Entry<? extends K, ? extends V> e : m.entrySet()) {
            put(e.getKey(), e.getValue());
        }
    }

    @Override
    default void clear() {
        entrySet().clear();
    }

    @Override
    default Set<K> keySet() {
        return new AbstractSet<K>() {
            public Iterator<K> iterator() {
                return new Iterator<K>() {
                    final Iterator<Entry<K, V>> i = entrySet().iterator();

                    public boolean hasNext() {
                        return i.hasNext();
                    }

                    public K next() {
                        return i.next().getKey();
                    }

                    public void remove() {
                        i.remove();
                    }
                };
            }

            public int size() {
                return AbstractMap.this.size();
            }

            public boolean isEmpty() {
                return AbstractMap.this.isEmpty();
            }

            public void clear() {
                AbstractMap.this.clear();
            }

            public boolean contains(Object k) {
                return AbstractMap.this.containsKey(k);
            }
        };
    }

    @Override
    default Collection<V> values() {
        return new AbstractCollection<V>() {
            public Iterator<V> iterator() {
                return new Iterator<V>() {
                    final Iterator<Entry<K, V>> i = entrySet().iterator();

                    public boolean hasNext() {
                        return i.hasNext();
                    }

                    public V next() {
                        return i.next().getValue();
                    }

                    public void remove() {
                        i.remove();
                    }
                };
            }

            public int size() {
                return AbstractMap.this.size();
            }

            public boolean isEmpty() {
                return AbstractMap.this.isEmpty();
            }

            public void clear() {
                AbstractMap.this.clear();
            }

            public boolean contains(Object v) {
                return AbstractMap.this.containsValue(v);
            }
        };
    }

    @Override
    default Set<Entry<K, V>> entrySet() {
        throw new UnsupportedOperationException();
    }
}
