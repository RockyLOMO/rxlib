package org.rx.util.function;

import lombok.SneakyThrows;

import java.util.function.BiFunction;

@FunctionalInterface
public interface TripleFunc<T1, T2, R> extends BiFunction<T1, T2, R> {
    R invoke(T1 t1, T2 t2) throws Throwable;

    @SneakyThrows
    @Override
    default R apply(T1 t1, T2 t2) {
        return invoke(t1, t2);
    }
}
