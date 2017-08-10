package org.rx.common;

/**
 * Created by wangxiaoming on 2016/7/28.
 */
@FunctionalInterface
public interface Func1<T1, T2> {
    T2 invoke(T1 arg);
}
