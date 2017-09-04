package org.rx.common;

public interface BiFunc<T1, T2, T3> {
    T3 invoke(T1 arg1, T2 arg2);
}
