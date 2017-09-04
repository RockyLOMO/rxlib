package org.rx.common;

public interface Func<T1, T2> {
    T2 invoke(T1 arg);
}
