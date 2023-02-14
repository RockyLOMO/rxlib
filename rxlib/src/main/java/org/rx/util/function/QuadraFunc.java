package org.rx.util.function;

import lombok.SneakyThrows;

@FunctionalInterface
public interface QuadraFunc<T1, T2, T3, R> {
    R invoke(T1 t1, T2 t2, T3 t3) throws Throwable;

    @SneakyThrows
    default R apply(T1 t1, T2 t2, T3 t3) {
        return invoke(t1, t2, t3);
    }
}
