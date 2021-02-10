package org.rx.util.function;

import java.io.Serializable;
import java.util.function.Function;

import static org.rx.core.App.sneakyInvoke;

@FunctionalInterface
public interface BiFunc<TP, TR> extends Serializable {
    TR invoke(TP param) throws Throwable;

    default Function<TP, TR> toFunction() {
        return p -> sneakyInvoke(() -> invoke(p));
    }
}
