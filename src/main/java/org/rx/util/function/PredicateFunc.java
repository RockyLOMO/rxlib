package org.rx.util.function;

import org.rx.exception.ApplicationException;

import java.util.function.Predicate;

@FunctionalInterface
public interface PredicateFunc<T> {
    boolean invoke(T t) throws Throwable;

    //sneakyInvoke has box/unbox issue
    default Predicate<T> toPredicate() {
        return p -> {
            try {
                return invoke(p);
            } catch (Throwable e) {
                throw ApplicationException.sneaky(e);
            }
        };
    }
}
