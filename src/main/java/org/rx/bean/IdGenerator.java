package org.rx.bean;

public class IdGenerator {
    public static final IdGenerator DEFAULT = new IdGenerator();
    private int val;
    private final int max;

    public IdGenerator() {
        this(0, Integer.MAX_VALUE);
    }

    public IdGenerator(int min, int max) {
        this.val = min;
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
            val = 0;
        }
        return i;
    }
}
