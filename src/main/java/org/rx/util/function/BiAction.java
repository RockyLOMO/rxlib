package org.rx.util.function;

import java.util.function.Consumer;

import static org.rx.core.Extends.sneakyInvoke;

@FunctionalInterface
public interface BiAction<T> {
    void invoke(T t) throws Throwable;

    default Consumer<T> toConsumer() {
        return p -> sneakyInvoke(() -> invoke(p));
    }
}
