package org.rx.util.function;

@FunctionalInterface
public interface ThrowableAction {
    void invoke() throws Throwable;
}
