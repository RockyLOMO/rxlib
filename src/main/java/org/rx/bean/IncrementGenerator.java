package org.rx.bean;

import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

@NoArgsConstructor
@AllArgsConstructor
public class IncrementGenerator {
    private volatile int val;

    public synchronized int next() {
        int i = ++val;
        if (i == Integer.MAX_VALUE) {
            val = 0;
        }
        return i;
    }
}
