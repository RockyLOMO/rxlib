package org.rx.util.function;

import lombok.SneakyThrows;

import java.util.function.Consumer;

@FunctionalInterface
public interface BiAction<T> extends Consumer<T> {
    void invoke(T t) throws Throwable;

    @SneakyThrows
    @Override
    default void accept(T t) {
        invoke(t);
    }
}
