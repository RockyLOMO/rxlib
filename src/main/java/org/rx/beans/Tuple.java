package org.rx.beans;

import lombok.EqualsAndHashCode;

@EqualsAndHashCode
public final class Tuple<T1, T2> {
    public static <T1, T2> Tuple<T1, T2> of(T1 t1, T2 t2) {
        return new Tuple<>(t1, t2);
    }

    public final T1 left;
    public final T2 right;

    public T1 getLeft() {
        return left;
    }

    public void setLeft(T1 left) {
        //this.left = left;
    }

    public T2 getRight() {
        return right;
    }

    public void setRight(T2 right) {
        //this.right = right;
    }

    private Tuple(T1 t1, T2 t2) {
        left = t1;
        right = t2;
    }
}
