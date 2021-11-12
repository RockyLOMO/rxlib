package org.rx.bean;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class IntTuple<T> implements Serializable {
    private static final long serialVersionUID = -2729116671111900937L;

    public static <T> IntTuple<T> of(int t1, T t2) {
        return new IntTuple<>(t1, t2);
    }

    public int left;
    public T right;
}
