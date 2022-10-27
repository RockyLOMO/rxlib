package org.rx.util.function;

@FunctionalInterface
public interface Action {
    void invoke() throws Throwable;

    default <T> Func<T> toFunc() {
        return () -> {
            invoke();
            return null;
        };
    }
}
