package org.rx.bean;

import io.netty.util.internal.ThreadLocalRandom;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.rx.core.Cache;
import org.rx.core.CachePolicy;
import org.rx.core.Linq;
import org.rx.core.cache.IntCompositeKey;
import org.rx.core.cache.MemoryCache;
import org.rx.util.function.BiFunc;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Predicate;

import static org.rx.core.Constants.NON_UNCHECKED;
import static org.rx.core.Extends.eq;
import static org.rx.core.Extends.require;

@SuppressWarnings(NON_UNCHECKED)
@NoArgsConstructor
public class RandomList<T> extends AbstractList<T> implements RandomAccess, Serializable {
    private static final long serialVersionUID = 675332324858046587L;
    public static final int DEFAULT_WEIGHT = 2;

    @AllArgsConstructor
    static class WeightElement<T> implements Serializable {
        private static final long serialVersionUID = 7199704019570049544L;
        private final T element;
        private int weight;
        private final DataRange<Integer> threshold = new DataRange<>();

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            WeightElement<?> that = (WeightElement<?>) o;
            return Objects.equals(element, that.element);
        }

        @Override
        public int hashCode() {
            return Objects.hash(element);
        }
    }

    final ReadWriteLock lock = new ReentrantReadWriteLock();
    final List<WeightElement<T>> elements = new ArrayList<>();
    int maxRandomValue;
    CopyOnWriteArrayList<T> temp;
    @Setter
    BiFunc<T, ? extends Comparable> sortFunc;

    public RandomList(Collection<T> elements) {
        addAll(elements);
    }

    public <S> T next(S steeringKey, int ttl) {
        return next(steeringKey, ttl, false);
    }

    public <S> T next(S steeringKey, int ttl, boolean isSliding) {
        Cache<IntCompositeKey<S>, T> cache = Cache.getInstance(MemoryCache.class);
        return cache.get(new IntCompositeKey<>(System.identityHashCode(this), steeringKey), k -> next(), isSliding ? CachePolicy.sliding(ttl) : CachePolicy.absolute(ttl));
    }

    public T next() {
        lock.writeLock().lock();
        try {
            if (elements.isEmpty()) {
                throw new NoSuchElementException();
            }

            if (maxRandomValue == 0) {
                WeightElement<T> holder = null;
                for (int i = 0; i < elements.size(); i++) {
                    WeightElement<T> element = elements.get(i);
                    if (i == 0) {
                        element.threshold.start = 0;
                        element.threshold.end = element.weight;
                    } else {
                        element.threshold.start = holder.threshold.end;
                        element.threshold.end = element.threshold.start + element.weight;
                    }
                    holder = element;
                }
                maxRandomValue = holder.threshold.end;
            }

            int v = ThreadLocalRandom.current().nextInt(maxRandomValue);
            //binary search
            int low = 0;
            int high = elements.size() - 1;
            while (low <= high) {
                int mid = (low + high) >>> 1;
                WeightElement<T> element = elements.get(mid);
                DataRange<Integer> threshold = element.threshold;
                if (threshold.end <= v) {
                    low = mid + 1;
                } else if (threshold.start > v) {
                    high = mid - 1;
                } else {
                    return element.element;
                }
            }
            throw new NoSuchElementException();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<T> aliveList() {
        lock.readLock().lock();
        try {
            return Linq.from(elements).where(p -> p.weight > 0).select(p -> p.element).toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    public int getWeight(T element) {
        lock.readLock().lock();
        try {
            return findElement(element, true).weight;
        } finally {
            lock.readLock().unlock();
        }
    }

    public void setWeight(T element, int weight) {
        require(weight, weight >= 0);

        lock.writeLock().lock();
        try {
            findElement(element, true).weight = weight;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private boolean change(boolean changed) {
        if (changed) {
            if (sortFunc != null) {
                elements.sort((o1, o2) -> {
                    Comparable c1 = sortFunc.apply(o1.element);
                    Comparable c2 = sortFunc.apply(o2.element);
                    if (c1 == null || c2 == null) {
                        return c1 == null ? (c2 == null ? 0 : 1) : -1;
                    }
                    return c1.compareTo(c2);
                });
            }
            temp = null;
            maxRandomValue = 0;
        }
        return changed;
    }

    private WeightElement<T> findElement(T element, boolean throwOnEmpty) {
        WeightElement<T> node = Linq.from(elements).firstOrDefault(p -> eq(p.element, element));
        if (throwOnEmpty && node == null) {
            throw new NoSuchElementException();
        }
        return node;
    }

    @Override
    public int size() {
        lock.readLock().lock();
        try {
            return elements.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public T get(int index) {
        lock.readLock().lock();
        try {
            return elements.get(index).element;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public boolean add(T element) {
        return add(element, DEFAULT_WEIGHT);
    }

    public boolean add(T element, int weight) {
        require(weight, weight >= 0);

        lock.writeLock().lock();
        try {
            boolean changed;
            WeightElement<T> node = findElement(element, false);
            if (node == null) {
                changed = elements.add(new WeightElement<>(element, weight));
            } else {
                if (changed = node.weight != weight) {
                    node.weight = weight;
                }
            }
            return change(changed);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void add(int index, T element) {
        add(index, element, DEFAULT_WEIGHT);
    }

    public boolean add(int index, T element, int weight) {
        require(weight, weight >= 0);

        lock.writeLock().lock();
        try {
            boolean changed;
            WeightElement<T> node = findElement(element, false);
            if (node == null) {
                elements.add(index, new WeightElement<>(element, weight));
                changed = true;
            } else {
                if (changed = node.weight != weight) {
                    node.weight = weight;
                }
            }
            return change(changed);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public T set(int index, T element) {
        return set(index, element, DEFAULT_WEIGHT);
    }

    public T set(int index, T element, int weight) {
        require(weight, weight >= 0);

        lock.writeLock().lock();
        try {
            WeightElement<T> previously = null;
            boolean changed;
            WeightElement<T> node = findElement(element, false);
            if (node == null) {
                previously = elements.set(index, new WeightElement<>(element, weight));
                changed = true;
            } else {
                if (changed = node.weight != weight) {
                    node.weight = weight;
                }
            }
            change(changed);
            return previously == null ? null : previously.element;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public boolean remove(Object element) {
        return removeIf(p -> eq(p, element));
    }

    @Override
    public T remove(int index) {
        lock.writeLock().lock();
        try {
            WeightElement<T> item = elements.remove(index);
            if (item == null) {
                return null;
            }
            change(true);
            return item.element;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public boolean removeIf(Predicate<? super T> filter) {
        lock.writeLock().lock();
        try {
            return change(elements.removeIf(p -> filter.test(p.element)));
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public boolean contains(Object element) {
        lock.readLock().lock();
        try {
            return findElement((T) element, false) != null;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Iterator<T> iterator() {
        lock.readLock().lock();
        try {
            if (temp == null) {
                temp = new CopyOnWriteArrayList<>(Linq.from(elements).select(p -> p.element).toList());
            }
            return temp.iterator();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public boolean addAll(Collection<? extends T> c) {
        lock.writeLock().lock();
        try {
            boolean changed = false;
            for (T t : c) {
                if (add(t)) {
                    changed = true;
                }
            }
            return change(changed);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        lock.writeLock().lock();
        try {
            return change(elements.removeAll(Linq.from(elements).join(c, (p, x) -> eq(p.element, x), (p, x) -> p).toList()));
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        lock.writeLock().lock();
        try {
            List<WeightElement<T>> items = Linq.from(elements).join(c, (p, x) -> eq(p.element, x), (p, x) -> p).toList();
            elements.clear();
            return change(elements.addAll(items));
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void clear() {
        lock.writeLock().lock();
        try {
            elements.clear();
            change(true);
        } finally {
            lock.writeLock().unlock();
        }
    }
}
