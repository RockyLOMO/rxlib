package org.rx.common;

/**
 * Created by wangxiaoming on 2016/3/11.
 */
public interface BiFunc<T1, T2, T3> {
    T3 invoke(T1 arg1, T2 arg2);
}
