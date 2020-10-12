package org.rx.util.function;

@FunctionalInterface
public interface PredicateFuncWithIndex<T> {
    boolean invoke(T t, int index) throws Throwable;
}
