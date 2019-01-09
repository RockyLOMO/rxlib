package org.rx.common;

import java.util.function.Supplier;

import static org.rx.common.Contract.require;

public final class Lazy<T> {
    private T                 value;
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
        this(() -> App.newInstance(type));
    }

    public Lazy(Supplier<T> supplier) {
        require(supplier);

        this.supplier = supplier;
    }
}
