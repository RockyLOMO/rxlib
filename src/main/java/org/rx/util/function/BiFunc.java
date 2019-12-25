package org.rx.util.function;

import org.rx.core.SystemException;

import java.util.function.Function;

@FunctionalInterface
public interface BiFunc<TP, TR> {
    TR invoke(TP param) throws Throwable;

    default Function<TP, TR> toFunction() {
        return p -> {
            try {
                return invoke(p);
            } catch (Throwable e) {
                throw SystemException.wrap(e);
            }
        };
    }
}
