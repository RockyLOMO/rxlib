package org.rx.bean;

import java.util.concurrent.atomic.AtomicInteger;

public class IntCounter {
    private final AtomicInteger counter;

    public IntCounter() {
        this(0);
    }

    public IntCounter(int initValue) {
        counter = new AtomicInteger(initValue);
    }

    public synchronized int next() {
        int i = counter.incrementAndGet();
        if (i == Integer.MAX_VALUE) {
            counter.set(0);
        }
        return i;
    }
}
