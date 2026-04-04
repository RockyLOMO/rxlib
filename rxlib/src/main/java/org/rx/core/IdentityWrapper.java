package org.rx.core;

import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import static org.rx.core.Extends.tryClose;

@NoArgsConstructor
@AllArgsConstructor
public class IdentityWrapper<T> implements AutoCloseable {
    public T instance;

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
