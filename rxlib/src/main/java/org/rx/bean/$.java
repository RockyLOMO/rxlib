package org.rx.bean;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@EqualsAndHashCode
public final class $<T> implements Serializable {
    private static final long serialVersionUID = -1049524743720496118L;

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
