package org.rx.common;

/**
 * Created by wangxiaoming on 2016/3/1.
 */
@FunctionalInterface
public interface Action1<T> {
    void invoke(T arg);
}
