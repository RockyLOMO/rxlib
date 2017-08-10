package org.rx.common;

/**
 * Created by wangxiaoming on 2016/3/11.
 */
@FunctionalInterface
public interface Action2<T1, T2> {
    void invoke(T1 arg1, T2 arg2);
}
