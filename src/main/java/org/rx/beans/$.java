package org.rx.beans;

import lombok.EqualsAndHashCode;

import java.io.Serializable;

@EqualsAndHashCode
public final class $<T> implements Serializable {
    public static <T> $<T> $() {
        return $(null);
    }

    public static <T> $<T> $(T val) {
        return new $<>(val);
    }

    public T v;

    private $(T val) {
        v = val;
    }

    @Override
    public String toString() {
        return v == null ? "" : v.toString();
    }
}
