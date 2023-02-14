package org.rx.core;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class IdentityWrapper<T> {
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
}
