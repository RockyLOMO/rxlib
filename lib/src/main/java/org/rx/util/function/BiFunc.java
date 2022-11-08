package org.rx.util.function;

import lombok.SneakyThrows;

import java.io.Serializable;
import java.util.function.Function;

@FunctionalInterface
public interface BiFunc<T, R> extends Function<T, R>, Serializable {
    R invoke(T t) throws Throwable;

    @SneakyThrows
    @Override
    default R apply(T t) {
        return invoke(t);
    }
}
