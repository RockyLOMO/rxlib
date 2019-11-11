package org.rx.beans;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

@AllArgsConstructor(access = AccessLevel.PRIVATE)
@EqualsAndHashCode
public final class $<T> implements Serializable {
    public static <T> $<T> $() {
        return $(null);
    }

    public static <T> $<T> $(T val) {
        return new $<>(val);
    }

    public T v;

    @Override
    public String toString() {
        return v == null ? "" : v.toString();
    }
}
