package org.rx.util.function;

import lombok.SneakyThrows;

import java.util.concurrent.Callable;
import java.util.function.Supplier;

@FunctionalInterface
public interface Func<T> extends Callable<T>, Supplier<T> {
    T invoke() throws Throwable;

    @SneakyThrows
    @Override
    default T call() throws Exception {
        return invoke();
    }

    @SneakyThrows
    @Override
    default T get() {
        return invoke();
    }
}
