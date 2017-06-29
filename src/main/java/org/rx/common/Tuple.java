package org.rx.common;

/**
 * Created by wangxiaoming on 2016/3/9.
 */
public class Tuple<T1, T2> extends NStruct {
    public final T1 Item1;
    public final T2 Item2;

    public Tuple(T1 t1, T2 t2) {
        Item1 = t1;
        Item2 = t2;
    }
}
