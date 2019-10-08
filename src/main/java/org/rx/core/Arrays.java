package org.rx.core;

import org.apache.commons.lang3.ArrayUtils;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;

import static org.rx.core.Contract.require;

/**
 * System.arraycopy();
 * Arrays.copyOf();
 */
public class Arrays extends ArrayUtils {
    public static <T> List<T> toList(T one) {
        require(one);

        T[] arr = (T[]) Array.newInstance(one.getClass(), 1);
        arr[0] = one;
        return toList(arr);
    }

    @SafeVarargs
    public static <T> List<T> toList(T... items) {
        require((Object) items);

        List<T> list = new ArrayList<>(items.length);
        for (T t : items) {
            list.add(t);
        }
        return list;
    }

    public static boolean equals(byte[] a, byte[] b) {
        return java.util.Arrays.equals(a, b);
    }
}