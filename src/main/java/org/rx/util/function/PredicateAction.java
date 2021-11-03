package org.rx.util.function;

@FunctionalInterface
public interface PredicateAction {
    boolean invoke() throws Throwable;
}
