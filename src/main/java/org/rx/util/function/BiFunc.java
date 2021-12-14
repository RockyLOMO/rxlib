package org.rx.util.function;

import org.rx.exception.ApplicationException;

import java.io.Serializable;
import java.util.function.Function;

@FunctionalInterface
public interface BiFunc<TP, TR> extends Serializable {
    TR invoke(TP param) throws Throwable;

    default Function<TP, TR> toFunction() {
        return p -> {
            try {
                return invoke(p);
            } catch (Throwable e) {
                throw ApplicationException.sneaky(e);
            }
        };
    }
}
