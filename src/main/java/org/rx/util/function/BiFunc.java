package org.rx.util.function;

@FunctionalInterface
public interface BiFunc<TParam, TResult> {
    TResult invoke(TParam param) throws Throwable;
}
