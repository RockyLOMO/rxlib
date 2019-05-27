package org.rx.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
public final class Tuple<T1, T2> implements Serializable {
    public static <T1, T2> Tuple<T1, T2> of(T1 t1, T2 t2) {
        return new Tuple<>(t1, t2);
    }

    public T1 left;
    public T2 right;

    public Tuple(T1 t1, T2 t2) {
        left = t1;
        right = t2;
    }
}
