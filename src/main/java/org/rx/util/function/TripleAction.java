package org.rx.util.function;

import org.rx.core.SystemException;

import java.util.function.BiConsumer;

@FunctionalInterface
public interface TripleAction<T1, T2> {
    void invoke(T1 t1, T2 t2) throws Throwable;

    default BiConsumer<T1, T2> toConsumer() {
        return (p1, p2) -> {
            try {
                invoke(p1, p2);
            } catch (Throwable e) {
                throw SystemException.wrap(e);
            }
        };
    }
}
