package org.rx.util.function;

import lombok.SneakyThrows;

import java.util.concurrent.Callable;
import java.util.function.Supplier;

@FunctionalInterface
public interface Func<T> extends Callable<T>, Supplier<T> {
    T apply() throws Throwable;

    @SneakyThrows
    @Override
    default T call() throws Exception {
        return apply();
    }

    @SneakyThrows
    @Override
    default T get() {
        return apply();
    }
}
