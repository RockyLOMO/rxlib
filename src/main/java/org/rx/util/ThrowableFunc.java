package org.rx.util;

@FunctionalInterface
public interface ThrowableFunc<T, TR> {
    TR invoke(T t) throws Throwable;
}
