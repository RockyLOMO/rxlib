package org.rx.beans;

import lombok.AllArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.rx.core.NQuery;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

import static org.rx.core.Contract.require;

public class RandomList<T> implements Collection<T>, Serializable {
    @AllArgsConstructor
    private static class WeightElement<T> {
        private final T element;
        private int weight;
        private final DataRange<Integer> threshold = new DataRange<>();
    }

    private final List<WeightElement<T>> elements = new ArrayList<>();
    private volatile int maxRandomValue;

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
        return findElement((T) element) != null;
    }

    @NotNull
    @Override
    public Iterator<T> iterator() {
        return new Iterator<T>() {
            int offset = -1;

            @Override
            public boolean hasNext() {
                return ++offset < elements.size();
            }

            @Override
            public T next() {
                return elements.get(offset).element;
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
        add(t, 2);
        return true;
    }

    public boolean add(T element, int weight) {
        require(weight, weight >= 0);

        boolean changed = false;
        WeightElement<T> weightElement = findElement(element);
        if (weightElement == null) {
            elements.add(new WeightElement<>(element, weight));
            changed = true;
        } else {
            weightElement.weight = weight;
        }
        maxRandomValue = 0;
        return changed;
    }

    public T next() {
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
            maxRandomValue = hold.threshold.end + 1;
        }
        Integer v = ThreadLocalRandom.current().nextInt(maxRandomValue);
        return NQuery.of(elements).single(p -> p.threshold.has(v)).element;
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

    public int getWeight(T element) {
        WeightElement<T> weightElement = findElement(element);
        if (weightElement == null) {
            throw new NoSuchElementException();
        }
        return weightElement.weight;
    }

    private WeightElement<T> findElement(T element) {
        return NQuery.of(elements).where(p -> p.element == element).firstOrDefault();
    }

    @Override
    public boolean remove(Object element) {
        return elements.removeIf(p -> p.element == element);
    }

    @Override
    public boolean containsAll(@NotNull Collection<?> c) {
        return NQuery.of(elements).all(c::contains);
    }

    @Override
    public boolean addAll(@NotNull Collection<? extends T> c) {
        boolean changed = false;
        for (T t : c) {
            if (add(t)) {
                changed = true;
            }
        }
        return changed;
    }

    @Override
    public boolean removeAll(@NotNull Collection<?> c) {
        boolean changed = false;
        for (Object o : c) {
            if (remove(o)) {
                changed = true;
            }
        }
        return changed;
    }

    @Override
    public boolean retainAll(@NotNull Collection<?> c) {
        int size = elements.size();
        elements.clear();
        elements.addAll(NQuery.of(elements).join(c, (p, x) -> p.element == x, (p, x) -> p).asCollection());
        return size != elements.size();
    }

    @Override
    public void clear() {
        elements.clear();
        maxRandomValue = 0;
    }
}
