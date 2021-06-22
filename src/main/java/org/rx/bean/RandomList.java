package org.rx.bean;

import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Arrays;
import org.rx.core.NQuery;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;

import static org.rx.core.App.NON_WARNING;
import static org.rx.core.App.require;

@SuppressWarnings(NON_WARNING)
@Slf4j
public class RandomList<T> implements Collection<T>, Serializable {
    private static final int DEFAULT_WEIGHT = 2;

    @AllArgsConstructor
    private static class WeightElement<T> implements Serializable {
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

    private final List<WeightElement<T>> elements = new CopyOnWriteArrayList<>();
    private volatile int maxRandomValue;

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
        Integer v = ThreadLocalRandom.current().nextInt(maxRandomValue);
//        NQuery<WeightElement<T>> q = NQuery.of(elements);
//        log.debug("{}\tnext {}/{}", q.select(p -> String.format("%s threshold[%s-%s]", p.element, p.threshold.start, p.threshold.end)).toJoinString(",", p -> p), v, maxRandomValue);
//        return q.single(p -> p.threshold.has(v)).element;

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

    public int getWeight(T element) {
        WeightElement<T> weightElement = findElement(element, true);
        synchronized (weightElement) {
            return weightElement.weight;
        }
    }

    public void setWeight(T element, int weight) {
        WeightElement<T> weightElement = findElement(element, true);
        synchronized (weightElement) {
            weightElement.weight = weight;
        }
    }

    private WeightElement<T> findElement(int index) {
        return elements.get(index);
    }

    private WeightElement<T> findElement(T element, boolean throwOnEmpty) {
        WeightElement<T> weightElement = NQuery.of(elements).firstOrDefault(p -> p.element == element);
        if (throwOnEmpty && weightElement == null) {
            throw new NoSuchElementException();
        }
        return weightElement;
    }

    @Override
    public int size() {
        return elements.size();
    }

    @Override
    public boolean isEmpty() {
        return elements.isEmpty();
    }

    @Override
    public boolean contains(Object element) {
        return findElement((T) element, false) != null;
    }

    @Override
    public boolean containsAll(Collection<?> c) {
        return NQuery.of(elements).all(c::contains);
    }

    @Override
    public Iterator<T> iterator() {
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
    public Object[] toArray() {
        if (elements.isEmpty()) {
            return Arrays.EMPTY_OBJECT_ARRAY;
        }
        return NQuery.of(elements).select(p -> p.element).toArray();
    }

    @Override
    public <T1> T1[] toArray(@NonNull T1[] a) {
        System.arraycopy(toArray(), 0, a, 0, a.length);
        return a;
    }

    @Override
    public boolean add(T element) {
        return add(element, DEFAULT_WEIGHT);
    }

    public boolean add(T element, int weight) {
        require(weight, weight >= 0);

        boolean changed;
        WeightElement<T> weightElement = findElement(element, false);
        if (weightElement == null) {
            elements.add(new WeightElement<>(element, weight));
            changed = true;
        } else {
            synchronized (weightElement) {
                if (changed = weightElement.weight != weight) {
                    weightElement.weight = weight;
                }
            }
        }
        return change(changed);
    }

    @Override
    public boolean remove(Object element) {
        return change(elements.removeIf(p -> p.element == element));
    }

    @Override
    public boolean addAll(Collection<? extends T> c) {
        return change(elements.addAll(NQuery.of(c).select(p -> new WeightElement<T>(p, DEFAULT_WEIGHT)).toList()));
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        List<WeightElement<T>> items = NQuery.of(elements).join(c, (p, x) -> p.element == x, (p, x) -> p).toList();
        return change(elements.removeAll(c));
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        List<WeightElement<T>> items = NQuery.of(elements).join(c, (p, x) -> p.element == x, (p, x) -> p).toList();
        elements.clear();
        return change(elements.addAll(items));
    }

    @Override
    public void clear() {
        elements.clear();
        change(true);
    }
}
