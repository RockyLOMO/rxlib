package org.rx.util.function;

import lombok.SneakyThrows;

@FunctionalInterface
public interface Action extends Runnable {
    void apply() throws Throwable;

    default <T> Func<T> toFunc() {
        return () -> {
            apply();
            return null;
        };
    }

    @SneakyThrows
    @Override
    default void run() {
        apply();
    }
}
