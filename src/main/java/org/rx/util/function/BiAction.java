package org.rx.util.function;

@FunctionalInterface
public interface BiAction<T> {
    void invoke(T t) throws Throwable;
}
