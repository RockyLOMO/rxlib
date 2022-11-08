package org.rx.util.function;

import lombok.SneakyThrows;

@FunctionalInterface
public interface QuadraAction<T1, T2, T3> {
    void invoke(T1 t1, T2 t2, T3 t3) throws Throwable;

    @SneakyThrows
    default void accept(T1 t1, T2 t2, T3 t3) {
        invoke(t1, t2, t3);
    }
}
