package org.rx.util.function;

@FunctionalInterface
public interface ThrowableFunc<T> {
    T invoke() throws Throwable;
}
