package org.rx.util.function;

import lombok.SneakyThrows;

import java.util.function.Consumer;

import static org.rx.core.Extends.sneakyInvoke;

@FunctionalInterface
public interface BiAction<T> extends Consumer<T> {
    void invoke(T t) throws Throwable;

    @SneakyThrows
    @Override
    default void accept(T t) {
        invoke(t);
    }
}
