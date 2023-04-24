package org.rx.bean;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

//ReferenceIdentityMap
public class WeakIdentityMap<K, V> implements AbstractMap<K, V> {
    final Map<WeakReference<K>, V> map;
    final ReferenceQueue<K> refQueue = new ReferenceQueue<>();
    transient MapView.EntrySetView<WeakReference<K>, K, V> entrySet;

    public WeakIdentityMap() {
        this(16);
    }

    public WeakIdentityMap(int initialCapacity) {
        map = new ConcurrentHashMap<>(initialCapacity);
    }

    @Override
    public int size() {
        expunge();
        return map.size();
    }

    @Override
    public V get(Object key) {
        expunge();
        Objects.requireNonNull(key, "key");
        WeakReference<K> keyref = new IdentityWeakReference<>((K) key);
        return map.get(keyref);
    }

    @Override
    public V put(K key, V value) {
        expunge();
        Objects.requireNonNull(key, "key");
        WeakReference<K> keyref = new IdentityWeakReference<>(key, refQueue);
        return map.put(keyref, value);
    }

    @Override
    public V remove(Object key) {
        expunge();
        Objects.requireNonNull(key, "key");
        WeakReference<K> keyref = new IdentityWeakReference<>((K) key);
        return map.remove(keyref);
    }

    @Override
    public Set<Entry<K, V>> entrySet() {
        expunge();
        MapView.EntrySetView<WeakReference<K>, K, V> es;
        return (es = entrySet) != null ? es : (entrySet = new MapView.EntrySetView<>(map, Reference::get));
    }

    @Override
    public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
        expunge();
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(mappingFunction, "mappingFunction");
        WeakReference<K> keyref = new IdentityWeakReference<>(key, refQueue);
        return map.computeIfAbsent(keyref, p -> mappingFunction.apply(key));
    }

//    public Stream<K> keysForValue(V value) {
//        return map.entrySet().stream().filter(p -> p.getValue() == value).map(p -> p.getKey().get()).filter(Objects::nonNull);
//    }
//
//    public void purgeValue(V value) {
//        expunge();
//        Objects.requireNonNull(value, "value");
//        map.forEach((k, v) -> {
//            if (value.equals(v)) {
//                map.remove(k, v);
//            }
//        });
//    }

    private void expunge() {
        Reference<? extends K> ref;
        while ((ref = refQueue.poll()) != null) {
            map.remove(ref);
        }
    }

    /**
     * WeakReference where equals and hashCode are based on the
     * referent.  More precisely, two objects are equal if they are
     * identical or if they both have the same non-null referent.  The
     * hashCode is the value the original referent had.  Even if the
     * referent is cleared, the hashCode remains.  Thus, objects of
     * this class can be used as keys in hash-based maps and sets.
     */
    private static class IdentityWeakReference<T> extends WeakReference<T> {
        final int hashCode;

        IdentityWeakReference(T o) {
            this(o, null);
        }

        IdentityWeakReference(T o, ReferenceQueue<T> q) {
            super(o, q);
            this.hashCode = (o == null) ? 0 : System.identityHashCode(o);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof WeakIdentityMap.IdentityWeakReference<?>)) {
                return false;
            }
            Object got = get();
            return got != null && got == ((IdentityWeakReference<?>) o).get();
        }

        @Override
        public int hashCode() {
            return hashCode;
        }
    }
}
