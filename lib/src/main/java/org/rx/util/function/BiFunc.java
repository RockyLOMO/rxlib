package org.rx.util.function;

import lombok.SneakyThrows;

import java.io.Serializable;
import java.util.function.Function;

@FunctionalInterface
public interface BiFunc<TP, TR> extends Function<TP, TR>, Serializable {
    TR invoke(TP param) throws Throwable;

    @SneakyThrows
    @Override
    default TR apply(TP tp) {
        return invoke(tp);
    }
}
