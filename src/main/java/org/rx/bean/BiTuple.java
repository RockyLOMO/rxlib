package org.rx.bean;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public final class BiTuple<T1, T2, T3> implements Serializable {
    public static <T1, T2, T3> BiTuple<T1, T2, T3> of(T1 t1, T2 t2, T3 t3) {
        return new BiTuple<>(t1, t2, t3);
    }

    public T1 left;
    public T2 middle;
    public T3 right;
}
