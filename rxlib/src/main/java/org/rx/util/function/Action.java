package org.rx.util.function;

import lombok.SneakyThrows;

@FunctionalInterface
public interface Action extends Runnable {
    void invoke() throws Throwable;

    default <T> Func<T> toFunc() {
        return () -> {
            invoke();
            return null;
        };
    }

    @SneakyThrows
    @Override
    default void run() {
        invoke();
    }
}
