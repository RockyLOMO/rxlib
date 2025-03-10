package org.rx.bean;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.AbstractMap;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Tuple<T1, T2> implements Serializable {
    private static final long serialVersionUID = 5110049327430282262L;
    static final Tuple EMPTY = new Tuple<>();

    public static <T1, T2> Tuple<T1, T2> empty() {
        return EMPTY;
    }

    public static <T1, T2> Tuple<T1, T2> of(T1 t1, T2 t2) {
        return new Tuple<>(t1, t2);
    }

    public static <T1, T2> Map.Entry<T1, T2> toMapEntry(T1 t1, T2 t2) {
        return new AbstractMap.SimpleImmutableEntry<>(t1, t2);
    }

    public T1 left;
    public T2 right;
}
