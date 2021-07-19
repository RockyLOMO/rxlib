package org.rx.bean;

import java.io.Serializable;

public class UShortPair implements Serializable {
    private static final long serialVersionUID = 2437920675997508893L;
    static final int SHARED_SHIFT = 16;
    static final int SHARED_UNIT = (1 << SHARED_SHIFT);
    static final int EXCLUSIVE_MASK = (1 << SHARED_SHIFT) - 1;

    int value;

    public int getShort0() {
        return value >>> SHARED_SHIFT;
    }

    public int getShort1() {
        return value & EXCLUSIVE_MASK;
    }

    //    c + SHARED_UNIT
//    c + acquires
    public void addShort0(int val) {
        value += SHARED_UNIT * val;
    }

    public void addShort1(int val) {
        value += val;
    }
}
