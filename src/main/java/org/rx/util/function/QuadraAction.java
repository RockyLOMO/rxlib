package org.rx.util.function;

@FunctionalInterface
public interface QuadraAction<T1, T2, T3> {
    void invoke(T1 t1, T2 t2, T3 t3) throws Throwable;
}
