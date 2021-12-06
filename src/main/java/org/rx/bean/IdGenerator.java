package org.rx.bean;

public class IdGenerator {
    public static final IdGenerator DEFAULT = new IdGenerator();
    private final int min, max;
    private int val;

    public IdGenerator() {
        this(0, Integer.MAX_VALUE);
    }

    public IdGenerator(int min, int max) {
        val = this.min = min;
        this.max = max;
    }

    public synchronized int get() {
        return val;
    }

    public synchronized void set(int value) {
        val = value;
    }

    public synchronized int increment() {
        int i = ++val;
        if (i == max) {
            val = min;
        }
        return i;
    }
}
