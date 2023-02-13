package org.rx.util.function;

import lombok.SneakyThrows;

import java.util.function.Predicate;

@FunctionalInterface
public interface PredicateFunc<T> extends Predicate<T> {
    boolean invoke(T t) throws Throwable;

    @SneakyThrows
    @Override
    default boolean test(T t) {
        return invoke(t);
    }
}
