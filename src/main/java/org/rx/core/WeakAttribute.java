package org.rx.core;

public interface WeakAttribute {
    default <TV> TV attr() {
        return Container.<Object, TV>weakMap().get(this);
    }

    default <TV> TV attr(TV v) {
        return Container.<Object, TV>weakMap().put(this, v);
    }
}
