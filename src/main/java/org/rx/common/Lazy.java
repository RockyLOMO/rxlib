package org.rx.common;

import java.util.function.Supplier;

import static org.rx.common.Contract.require;
import static org.rx.common.Contract.wrapCause;

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
        this(() -> {
            try {
                return type.newInstance();
            } catch (ReflectiveOperationException ex) {
                throw wrapCause(ex);
            }
        });
    }

    public Lazy(Supplier<T> supplier) {
        require(supplier);

        this.supplier = supplier;
    }
}
