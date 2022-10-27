package org.rx.util.function;

import java.util.function.BiConsumer;

import static org.rx.core.Extends.sneakyInvoke;

@FunctionalInterface
public interface TripleAction<T1, T2> {
    void invoke(T1 t1, T2 t2) throws Throwable;

    default BiConsumer<T1, T2> toConsumer() {
        return (p1, p2) -> sneakyInvoke(() -> invoke(p1, p2));
    }
}
