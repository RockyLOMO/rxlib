package org.rx.beans;

import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.ThreadLocalRandom;

public class RandomList<T> implements Iterable<T> {
    @RequiredArgsConstructor
    static class WeightElement<T> {
        public final T element;
        public final int weight;
        public final DataRange<Integer> threshold = new DataRange<>();
    }

    private final List<WeightElement<T>> elements = new ArrayList<>();
    private int maxRandomValue;

    public RandomList<T> add(T element, int weight) {
        elements.add(new WeightElement<>(element, weight));
        maxRandomValue = 0;
        return this;
    }

    public int getWeight(T element) {
        int i = elements.indexOf(element);
        if (i == -1) {
            throw new NoSuchElementException();
        }
        return elements.get(i).weight;
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
