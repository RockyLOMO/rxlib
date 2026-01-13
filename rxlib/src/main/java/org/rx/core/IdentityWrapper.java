package org.rx.core;

import lombok.RequiredArgsConstructor;

import static org.rx.core.Extends.tryClose;

@RequiredArgsConstructor
public class IdentityWrapper<T> implements AutoCloseable {
    public final T instance;

    public boolean equals(Object other) {
        return other instanceof IdentityWrapper && ((IdentityWrapper<?>) other).instance == this.instance;
    }

    public int hashCode() {
        return System.identityHashCode(this.instance);
    }

    @Override
    public String toString() {
        return String.valueOf(instance);
    }

    @Override
    public void close() throws Exception {
        tryClose(instance);
    }
}
