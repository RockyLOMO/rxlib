package org.rx.core;

import java.io.Serializable;

public interface Extends extends Serializable {
    default <TV> TV attr() {
        return Container.<Object, TV>weakMap().get(this);
    }

    default <TV> TV attr(TV v) {
        return Container.<Object, TV>weakMap().put(this, v);
    }

    default <T> T deepClone() {
        return (T) App.deepClone(this);
    }
}
