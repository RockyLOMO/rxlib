package org.rx.common;

public final class BiTuple<T1, T2, T3> extends NStruct {
    public static <T1, T2, T3> BiTuple<T1, T2, T3> of(T1 t1, T2 t2, T3 t3) {
        return new BiTuple<>(t1, t2, t3);
    }

    public final T1 left;
    public final T2 middle;
    public final T3 right;

    public T1 getLeft() {
        return left;
    }

    public void setLeft(T1 left) {
        //this.left = left;
    }

    public T2 getMiddle() {
        return middle;
    }

    public void setMiddle(T2 middle) {
        //this.middle = middle;
    }

    public T3 getRight() {
        return right;
    }

    public void setRight(T3 right) {
        //this.right = right;
    }

    private BiTuple(T1 t1, T2 t2, T3 t3) {
        left = t1;
        middle = t2;
        right = t3;
    }
}
