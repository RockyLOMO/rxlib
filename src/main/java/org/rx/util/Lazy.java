package org.rx.util;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.rx.util.function.Func;

@RequiredArgsConstructor
public final class Lazy<T> {
    private Func<T> func;
    private volatile T value;

    public boolean isValueCreated() {
        return value != null;
    }

    @SneakyThrows
    public T getValue() {
        if (value == null) {
            synchronized (this) {
                if (value == null) {
                    value = func.invoke();
                    func = null;
                }
            }
        }
        return value;
    }

    public Lazy(@NonNull Func<T> func) {
        this.func = func;
    }
}
