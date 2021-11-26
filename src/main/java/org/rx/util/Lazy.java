package org.rx.util;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.rx.util.function.Func;

@RequiredArgsConstructor
public final class Lazy<T> {
    final Func<T> func;
    private volatile T value;

    public boolean isValueCreated() {
        return value != null;
    }

    @SneakyThrows
    public T getValue() {
        if (value == null) {
            synchronized (func) {
                if (value == null) {
                    value = func.invoke();
                }
            }
        }
        return value;
    }
}
