package org.rx.bean;

import lombok.NonNull;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

//不要放值类型
//ReferenceMap, ReferenceIdentityMap
public class ConcurrentWeakMap<K, V> implements AbstractMap<K, V> {
    final ReferenceQueue<K> refQueue = new ReferenceQueue<>();
    final Map<Reference<K>, V> map;
    final boolean identityReference;
    transient MapView.EntrySetView<Reference<K>, K, V> entrySet;

    public ConcurrentWeakMap(boolean identityReference) {
        this(identityReference, 16);
    }

    public ConcurrentWeakMap(boolean identityReference, int initialCapacity) {
        map = new ConcurrentHashMap<>(initialCapacity);
        this.identityReference = identityReference;
    }

    Reference<K> toKeyReference(K key) {
        return identityReference ? new IdentityWeakReference<>(key, refQueue) : new WeakReference<>(key, refQueue);
    }

    @Override
    public int size() {
        expunge();
        return map.size();
    }

    @Override
    public V get(@NonNull Object key) {
        expunge();
        return map.get(toKeyReference((K) key));
    }

    @Override
    public V put(@NonNull K key, V value) {
        expunge();
        return map.put(toKeyReference(key), value);
    }

    @Override
    public V remove(@NonNull Object key) {
        expunge();
        return map.remove(toKeyReference((K) key));
    }

    @Override
    public Set<Entry<K, V>> entrySet() {
        expunge();
        MapView.EntrySetView<Reference<K>, K, V> es;
        return (es = entrySet) != null ? es : (entrySet = new MapView.EntrySetView<>(map, Reference::get));
    }

    @Override
    public V computeIfAbsent(@NonNull K key, Function<? super K, ? extends V> mappingFunction) {
        expunge();
        Objects.requireNonNull(mappingFunction, "mappingFunction");
        return map.computeIfAbsent(toKeyReference(key), p -> mappingFunction.apply(key));
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
            if (!(o instanceof IdentityWeakReference)) {
                return false;
            }
            return get() == ((IdentityWeakReference<?>) o).get();
        }

        @Override
        public int hashCode() {
            return hashCode;
        }
    }
}
