package org.rx.util.function;

import lombok.SneakyThrows;

@FunctionalInterface
public interface PredicateFuncWithIndex<T> {
    boolean invoke(T t, int index) throws Throwable;

    @SneakyThrows
    default boolean test(T t, int index) {
        return invoke(t, index);
    }
}
