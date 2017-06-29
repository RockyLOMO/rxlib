package org.rx.common;

/**
 * Created by wangxiaoming on 2016/3/21.
 */
public class Tuple2<T1, T2, T3> extends Tuple<T1, T2> {
    public final T3 Item3;

    public Tuple2(T1 t1, T2 t2, T3 t3) {
        super(t1, t2);
        Item3 = t3;
    }
}
