package org.rx.bean;

public class IncrementGenerator {
    private int val;
    private final int max;

    public IncrementGenerator() {
        this(0, Integer.MAX_VALUE);
    }

    public IncrementGenerator(int val, int max) {
        this.val = val;
        this.max = max;
    }

    public synchronized int next() {
        int i = ++val;
        if (i == max) {
            val = 0;
        }
        return i;
    }
}
