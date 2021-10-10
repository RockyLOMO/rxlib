package org.rx.bean;

import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Cache;
import org.rx.core.CacheExpirations;
import org.rx.core.NQuery;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

import static org.rx.core.App.*;

@SuppressWarnings(NON_WARNING)
@Slf4j
@NoArgsConstructor
public class RandomList<T> extends AbstractList<T> implements RandomAccess, Serializable {
    private static final long serialVersionUID = 675332324858046587L;
    private static final int DEFAULT_WEIGHT = 2;

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

    public RandomList(Collection<T> elements) {
        addAll(elements);
    }

    public <S> T next(S steeringObj, int ttl) {
        return next(steeringObj, ttl, false);
    }

    public <S> T next(S steeringObj, int ttl, boolean isSliding) {
        Cache<S, T> cache = Cache.getInstance(Cache.MEMORY_CACHE);
        return cache.get(steeringObj, k -> next(), isSliding ? CacheExpirations.sliding(ttl) : CacheExpirations.absolute(ttl));
    }

    public synchronized T next() {
        switch (elements.size()) {
            case 0:
                throw new NoSuchElementException();
            case 1:
                return elements.get(0).element;
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
            WeightElement<T> weightElement = elements.get(mid);
            DataRange<Integer> threshold = weightElement.threshold;
            if (threshold.end <= v) {
                low = mid + 1;
            } else if (threshold.start > v) {
                high = mid - 1;
            } else {
                return weightElement.element;
            }
        }
        throw new NoSuchElementException();
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

    public int getWeight(T element) {
        WeightElement<T> node = findElement(element, true);
        synchronized (node) {
            return node.weight;
        }
    }

    public void setWeight(T element, int weight) {
        WeightElement<T> node = findElement(element, true);
        synchronized (node) {
            node.weight = weight;
        }
    }

    @Override
    public synchronized int size() {
        return elements.size();
    }

    @Override
    public synchronized T get(int index) {
        return elements.get(index).element;
    }

    @Override
    public boolean add(T element) {
        return add(element, DEFAULT_WEIGHT);
    }

    public synchronized boolean add(T element, int weight) {
        require(weight, weight >= 0);

        boolean changed;
        WeightElement<T> node = findElement(element, false);
        if (node == null) {
            changed = elements.add(new WeightElement<>(element, weight));
        } else {
            synchronized (node) {
                if (changed = node.weight != weight) {
                    node.weight = weight;
                }
            }
        }
        return change(changed);
    }

    @Override
    public void add(int index, T element) {
        add(index, element, DEFAULT_WEIGHT);
    }

    public synchronized boolean add(int index, T element, int weight) {
        require(weight, weight >= 0);

        boolean changed;
        WeightElement<T> node = findElement(element, false);
        if (node == null) {
            elements.add(index, new WeightElement<>(element, weight));
            changed = true;
        } else {
            synchronized (node) {
                if (changed = node.weight != weight) {
                    node.weight = weight;
                }
            }
        }
        return change(changed);
    }

    @Override
    public synchronized T set(int index, T element) {
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
    }

    @Override
    public synchronized boolean remove(Object element) {
        return change(elements.removeIf(p -> eq(p.element, element)));
    }

    @Override
    public synchronized T remove(int index) {
        WeightElement<T> item = elements.remove(index);
        if (item == null) {
            return null;
        }
        change(true);
        return item.element;
    }

    @Override
    public synchronized boolean contains(Object element) {
        return findElement((T) element, false) != null;
    }

    @Override
    public synchronized Iterator<T> iterator() {
//        return NQuery.of(elements, true).select(p -> p.element).iterator();
        Iterator<WeightElement<T>> iterator = elements.iterator();
        return new Iterator<T>() {
            @Override
            public boolean hasNext() {
                return iterator.hasNext();
            }

            @Override
            public T next() {
                return iterator.next().element;
            }
        };
    }

    @Override
    public synchronized boolean addAll(Collection<? extends T> c) {
        return change(elements.addAll(NQuery.of(c).select(p -> new WeightElement<T>(p, DEFAULT_WEIGHT)).toList()));
    }

    @Override
    public synchronized boolean removeAll(Collection<?> c) {
        return change(elements.removeAll(NQuery.of(elements).join(c, (p, x) -> eq(p.element, x), (p, x) -> p).toList()));
    }

    @Override
    public synchronized boolean retainAll(Collection<?> c) {
        List<WeightElement<T>> items = NQuery.of(elements).join(c, (p, x) -> eq(p.element, x), (p, x) -> p).toList();
        elements.clear();
        return change(elements.addAll(items));
    }

    @Override
    public synchronized void clear() {
        elements.clear();
        change(true);
    }
}
