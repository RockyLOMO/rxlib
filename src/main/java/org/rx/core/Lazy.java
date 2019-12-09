package org.rx.core;

import lombok.RequiredArgsConstructor;

import java.util.function.Supplier;

@RequiredArgsConstructor
public final class Lazy<T> {
    private T value;
    private final Supplier<T> supplier;

    public boolean isValueCreated() {
        return value != null;
    }

    public T getValue() {
        if (value == null) {
            synchronized (supplier) {
                if (value == null) {
                    value = supplier.get();
                }
            }
        }
        return value;
    }

    public Lazy(Class<T> type) {
        this(() -> Reflects.newInstance(type));
    }
}
