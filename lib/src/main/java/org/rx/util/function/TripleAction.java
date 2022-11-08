package org.rx.util.function;

import lombok.SneakyThrows;

import java.util.function.BiConsumer;

@FunctionalInterface
public interface TripleAction<T1, T2> extends BiConsumer<T1, T2> {
    void invoke(T1 t1, T2 t2) throws Throwable;

    @SneakyThrows
    @Override
    default void accept(T1 t1, T2 t2) {
        invoke(t1, t2);
    }
}
