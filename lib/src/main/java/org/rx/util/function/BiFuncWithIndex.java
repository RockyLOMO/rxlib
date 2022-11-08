package org.rx.util.function;

import lombok.SneakyThrows;

@FunctionalInterface
public interface BiFuncWithIndex<T, R> {
    R invoke(T t, int index) throws Throwable;

    @SneakyThrows
    default R apply(T t, int index) {
        return invoke(t, index);
    }
}
