package org.rx.common;

/**
 * Created by wangxiaoming on 2016/3/1.
 */
public interface ActionCallback<T> {
    void invoke(T arg);
}
