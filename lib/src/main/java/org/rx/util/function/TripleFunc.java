package org.rx.util.function;

import lombok.SneakyThrows;

import java.util.function.BiFunction;

@FunctionalInterface
public interface TripleFunc<T1, T2, TR> extends BiFunction<T1, T2, TR> {
    TR invoke(T1 param1, T2 param2) throws Throwable;

    @SneakyThrows
    @Override
    default TR apply(T1 t1, T2 t2) {
        return invoke(t1, t2);
    }
}
