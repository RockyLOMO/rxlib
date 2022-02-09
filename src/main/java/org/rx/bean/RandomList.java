package org.rx.bean;

import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import org.rx.core.Cache;
import org.rx.core.CachePolicy;
import org.rx.core.NQuery;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import io.netty.util.internal.ThreadLocalRandom;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static org.rx.core.App.*;
import static org.rx.core.Constants.NON_UNCHECKED;

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

    private final List<WeightElement<T>> elements = new ArrayList<>();
    private int maxRandomValue;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    public RandomList(Collection<T> elements) {
        addAll(elements);
    }

    public <S> T next(S steeringObj, int ttl) {
        return next(steeringObj, ttl, false);
    }

    public <S> T next(S steeringObj, int ttl, boolean isSliding) {
        Cache<S, T> cache = Cache.getInstance(Cache.MEMORY_CACHE);
        return cache.get(steeringObj, k -> next(), isSliding ? CachePolicy.sliding(ttl) : CachePolicy.absolute(ttl));
    }

    public T next() {
        lock.writeLock().lock();
        try {
            if (elements.isEmpty()) {
                throw new NoSuchElementException();
            }

            if (maxRandomValue == 0) {
                WeightElement<T> hold = null;
                for (int i = 0; i < elements.size(); i++) {
                    WeightElement<T> element = elements.get(i);
                    if (i == 0) {
                        element.threshold.start = 0;
                        element.threshold.end = element.weight;
                    } else {
                        element.threshold.start = hold.threshold.end;
                        element.threshold.end = element.threshold.start + element.weight;
                    }
                    hold = element;
                }
                maxRandomValue = hold.threshold.end;
            }

            int v = ThreadLocalRandom.current().nextInt(maxRandomValue);
            //二分法查找
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
            return NQuery.of(elements).where(p -> p.weight > 0).select(p -> p.element).toList();
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
            maxRandomValue = 0;
        }
        return changed;
    }

    private WeightElement<T> findElement(T element, boolean throwOnEmpty) {
        WeightElement<T> node = NQuery.of(elements).firstOrDefault(p -> eq(p.element, element));
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
        lock.writeLock().lock();
        try {
            boolean changed;
            WeightElement<T> node = findElement(element, false);
            if (node == null) {
                elements.set(index, new WeightElement<>(element, DEFAULT_WEIGHT));
                changed = true;
            } else {
                changed = false;
            }
            change(changed);
            return null;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public boolean remove(Object element) {
        lock.writeLock().lock();
        try {
            return change(elements.removeIf(p -> eq(p.element, element)));
        } finally {
            lock.writeLock().unlock();
        }
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
            return new CopyOnWriteArrayList<T>(NQuery.of(elements).select(p -> p.element).toList()).iterator();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public boolean addAll(Collection<? extends T> c) {
        lock.writeLock().lock();
        try {
            return change(elements.addAll(NQuery.of(c).select(p -> new WeightElement<T>(p, DEFAULT_WEIGHT)).toList()));
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        lock.writeLock().lock();
        try {
            return change(elements.removeAll(NQuery.of(elements).join(c, (p, x) -> eq(p.element, x), (p, x) -> p).toList()));
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        lock.writeLock().lock();
        try {
            List<WeightElement<T>> items = NQuery.of(elements).join(c, (p, x) -> eq(p.element, x), (p, x) -> p).toList();
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
