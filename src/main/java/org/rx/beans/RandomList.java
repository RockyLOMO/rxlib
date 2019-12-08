package org.rx.beans;

import lombok.AllArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.rx.core.NQuery;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.ThreadLocalRandom;

import static org.rx.core.Contract.require;

public class RandomList<T> implements Iterable<T> {
    @AllArgsConstructor
    static class WeightElement<T> {
        public final T element;
        public int weight;
        public final DataRange<Integer> threshold = new DataRange<>();
    }

    private final List<WeightElement<T>> elements = new ArrayList<>();
    private int maxRandomValue;

    public int size() {
        return elements.size();
    }

    public RandomList<T> add(T element, int weight) {
        require(weight, weight >= 0);

        WeightElement<T> weightElement = findElement(element);
        if (weightElement == null) {
            elements.add(new WeightElement<>(element, weight));
        } else {
            weightElement.weight = weight;
        }
        maxRandomValue = 0;
        return this;
    }

    private WeightElement<T> findElement(T element) {
        return NQuery.of(elements).where(p -> p.element == element).firstOrDefault();
    }

    public int getWeight(T element) {
        WeightElement<T> weightElement = findElement(element);
        if (weightElement == null) {
            throw new NoSuchElementException();
        }
        return weightElement.weight;
    }

    public RandomList<T> remove(T element) {
        elements.removeIf(p -> p.element == element);
        return this;
    }

    public RandomList<T> clear() {
        elements.clear();
        maxRandomValue = 0;
        return this;
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
            maxRandomValue = hold.threshold.end;
        }
        int v = ThreadLocalRandom.current().nextInt(maxRandomValue);
        //二分法查找
        int start = 1, end = elements.size() - 1;
        while (true) {
            int index = (start + end) / 2;
            if (v < elements.get(index).threshold.start) {
                end = index - 1;
            } else if (v >= elements.get(index).threshold.end) {
                start = index + 1;
            } else {
                return elements.get(index).element;
            }
        }
    }
}
