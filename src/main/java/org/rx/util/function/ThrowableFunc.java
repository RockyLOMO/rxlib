package org.rx.util.function;

@FunctionalInterface
public interface ThrowableFunc<T, TR> {
    TR invoke(T t) throws Throwable;
}
