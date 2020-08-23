package org.rx.bean;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.NQuery;

import javax.validation.constraints.NotNull;
import java.io.Serializable;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

import static org.rx.core.Contract.require;

@Slf4j
public class RandomList<T> implements Collection<T>, Serializable {
    @AllArgsConstructor
    private static class WeightElement<T> implements Serializable {
        private final T element;
        private int weight;
        private final DataRange<Integer> threshold = new DataRange<>();
    }

    private final List<WeightElement<T>> elements = new ArrayList<>();
    private volatile int maxRandomValue;

    public int getWeight(T element) {
        WeightElement<T> weightElement = findElement(element);
        if (weightElement == null) {
            throw new NoSuchElementException();
        }
        return weightElement.weight;
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
        Integer v = ThreadLocalRandom.current().nextInt(maxRandomValue);
        NQuery<WeightElement<T>> q = NQuery.of(elements);
        log.debug("{}\tnext {}/{}", q.select(p -> String.format("%s threshold[%s-%s]", p.element, p.threshold.start, p.threshold.end)).toJoinString(",", p -> p), v, maxRandomValue);
        return q.single(p -> p.threshold.has(v)).element;
        //二分法查找
//        int start = 1, end = elements.size() - 1;
//        while (true) {
//            int index = (start + end) / 2;
//            if (v < elements.get(index).threshold.start) {
//                end = index - 1;
//            } else if (v >= elements.get(index).threshold.end) {
//                start = index + 1;
//            } else {
//                return elements.get(index).element;
//            }
//        }
    }

    private synchronized WeightElement<T> findElement(int index) {
        return elements.get(index);
    }

    private synchronized WeightElement<T> findElement(T element) {
        return NQuery.of(elements).firstOrDefault(p -> p.element == element);
    }

    @Override
    public synchronized int size() {
        return elements.size();
    }

    @Override
    public synchronized boolean isEmpty() {
        return elements.isEmpty();
    }

    @Override
    public boolean contains(Object element) {
        return findElement((T) element) != null;
    }

    @Override
    public boolean containsAll(@NotNull Collection<?> c) {
        return NQuery.of(elements).all(c::contains);
    }

    @NotNull
    @Override
    public synchronized Iterator<T> iterator() {
        return new Iterator<T>() {
            int offset = -1;

            @Override
            public boolean hasNext() {
                return ++offset < size();
            }

            @Override
            public T next() {
                return findElement(offset).element;
            }
        };
    }

    @NotNull
    @Override
    public Object[] toArray() {
        return NQuery.of(elements).select(p -> p.element).toArray();
    }

    @NotNull
    @Override
    public <T1> T1[] toArray(@NotNull T1[] a) {
        System.arraycopy(toArray(), 0, a, 0, a.length);
        return a;
    }

    @Override
    public boolean add(T t) {
        return add(t, 2);
    }

    public synchronized boolean add(T element, int weight) {
        require(weight, weight >= 0);

        boolean changed;
        WeightElement<T> weightElement = findElement(element);
        if (weightElement == null) {
            elements.add(new WeightElement<>(element, weight));
            changed = true;
        } else {
            if (changed = weightElement.weight != weight) {
                weightElement.weight = weight;
            }
        }
        return change(changed);
    }

    private boolean change(boolean changed) {
        if (changed) {
            maxRandomValue = 0;
        }
        return changed;
    }

    @Override
    public synchronized boolean remove(Object element) {
        return change(elements.removeIf(p -> p.element == element));
    }

    @Override
    public synchronized boolean addAll(@NotNull Collection<? extends T> c) {
        boolean changed = false;
        for (T t : c) {
            if (add(t)) {
                changed = true;
            }
        }
        return changed;
    }

    @Override
    public synchronized boolean removeAll(@NotNull Collection<?> c) {
        boolean changed = false;
        for (Object o : c) {
            if (remove(o)) {
                changed = true;
            }
        }
        return changed;
    }

    @Override
    public synchronized boolean retainAll(@NotNull Collection<?> c) {
        int size = elements.size();
        List<WeightElement<T>> items = NQuery.of(elements).join(c, (p, x) -> p.element == x, (p, x) -> p).toList();
        elements.clear();
        elements.addAll(items);
        return change(size != elements.size());
    }

    @Override
    public synchronized void clear() {
        elements.clear();
        change(true);
    }
}
