package org.rx.util.function;

import org.rx.core.exception.InvalidException;

import java.util.function.Consumer;

@FunctionalInterface
public interface BiAction<T> {
    void invoke(T t) throws Throwable;

    default Consumer<T> toConsumer() {
        return p -> {
            try {
                invoke(p);
            } catch (Throwable e) {
                throw InvalidException.wrap(e);
            }
        };
    }
}
