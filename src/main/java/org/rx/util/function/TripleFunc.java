package org.rx.util.function;

import org.rx.core.SystemException;

import java.util.function.BiFunction;

@FunctionalInterface
public interface TripleFunc<TP1, T2, TR> {
    TR invoke(TP1 param1, T2 param2) throws Throwable;

    default BiFunction<TP1, T2, TR> toFunction() {
        return (p1, p2) -> {
            try {
                return invoke(p1, p2);
            } catch (Throwable e) {
                throw SystemException.wrap(e);
            }
        };
    }
}
