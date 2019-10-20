package org.rx.util.function;

import org.rx.core.SystemException;

import java.util.function.Function;

@FunctionalInterface
public interface BiFunc<TParam, TResult> {
    TResult invoke(TParam param) throws Throwable;

    default Function<TParam, TResult> toFunction() {
        return p -> {
            try {
                return invoke(p);
            } catch (Throwable e) {
                throw SystemException.wrap(e);
            }
        };
    }
}
