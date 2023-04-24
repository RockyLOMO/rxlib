package org.rx.bean;

import lombok.RequiredArgsConstructor;
import org.rx.util.function.BiFunc;

import java.io.Serializable;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

@RequiredArgsConstructor
public class MapView<KS, K, V> implements AbstractMap<K, V>, Serializable {
    private static final long serialVersionUID = 1937534858824236935L;
    final Map<KS, V> map;
    final BiFunc<KS, K> keyView;
    transient EntrySetView<KS, K, V> entrySet;

    @Override
    public int size() {
        return map.size();
    }

    @Override
    public V get(Object key) {
        Iterator<Entry<KS, V>> i = map.entrySet().iterator();
        if (key == null) {
            while (i.hasNext()) {
                Entry<KS, V> e = i.next();
                if (e.getKey() == null) {
                    return e.getValue();
                }
            }
        } else {
            while (i.hasNext()) {
                Entry<KS, V> e = i.next();
                if (key.equals(keyView.apply(e.getKey()))) {
                    return e.getValue();
                }
            }
        }
        return null;
    }

    @Override
    public V put(K key, V value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public V remove(Object key) {
        Iterator<Entry<KS, V>> i = map.entrySet().iterator();
        Entry<KS, V> correctEntry = null;
        if (key == null) {
            while (correctEntry == null && i.hasNext()) {
                Entry<KS, V> e = i.next();
                if (e.getKey() == null) {
                    correctEntry = e;
                }
            }
        } else {
            while (correctEntry == null && i.hasNext()) {
                Entry<KS, V> e = i.next();
                if (key.equals(e.getKey())) {
                    correctEntry = e;
                }
            }
        }

        V oldValue = null;
        if (correctEntry != null) {
            oldValue = correctEntry.getValue();
            i.remove();
        }
        return oldValue;
    }

    @Override
    public Set<Entry<K, V>> entrySet() {
        EntrySetView<KS, K, V> es;
        return (es = entrySet) != null ? es : (entrySet = new EntrySetView<>(map, keyView));
    }

    @RequiredArgsConstructor
    static final class EntrySetView<KS, K, V> extends AbstractSet<Entry<K, V>> {
        final Map<KS, V> map;
        final BiFunc<KS, K> keyView;

        @Override
        public int size() {
            return map.size();
        }

        @Override
        public void clear() {
            map.clear();
        }

        @Override
        public Iterator<Entry<K, V>> iterator() {
            Iterator<Entry<KS, V>> iterator = map.entrySet().iterator();
            return new Iterator<Entry<K, V>>() {
                Entry<KS, V> entry;
                K key;

                @Override
                public boolean hasNext() {
                    while (iterator.hasNext()) {
                        if ((key = keyView.apply((entry = iterator.next()).getKey())) == null) {
                            continue;
                        }
                        return true;
                    }
                    return false;
                }

                @Override
                public Entry<K, V> next() {
                    return new java.util.AbstractMap.SimpleEntry<K, V>(key, entry.getValue()) {
                        private static final long serialVersionUID = -1039216080459017222L;

                        @Override
                        public V setValue(V value) {
                            V v = entry.getValue();
                            super.setValue(value);
                            map.put(entry.getKey(), value);
                            return v;
                        }
                    };
                }

                @Override
                public void remove() {
                    iterator.remove();
                }
            };
        }
    }
}
