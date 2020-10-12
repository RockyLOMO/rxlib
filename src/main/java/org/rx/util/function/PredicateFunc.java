package org.rx.util.function;

import java.util.function.Predicate;

import static org.rx.core.Contract.sneakyInvoke;

@FunctionalInterface
public interface PredicateFunc<T> {
    boolean invoke(T t) throws Throwable;

    default Predicate<T> toPredicate() {
        return p -> sneakyInvoke(() -> invoke(p));
    }
}
