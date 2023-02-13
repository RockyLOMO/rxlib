package org.rx.util.function;

import lombok.SneakyThrows;

@FunctionalInterface
public interface PredicateAction {
    boolean invoke() throws Throwable;

    @SneakyThrows
    default boolean test() {
        return invoke();
    }
}
