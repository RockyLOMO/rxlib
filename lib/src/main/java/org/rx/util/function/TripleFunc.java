package org.rx.util.function;

import java.util.function.BiFunction;

import static org.rx.core.Extends.sneakyInvoke;

@FunctionalInterface
public interface TripleFunc<T1, T2, TR> {
    TR invoke(T1 param1, T2 param2) throws Throwable;

    default BiFunction<T1, T2, TR> toFunction() {
        return (p1, p2) -> sneakyInvoke(() -> invoke(p1, p2));
    }
}
