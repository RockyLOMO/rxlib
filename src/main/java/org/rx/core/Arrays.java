package org.rx.core;

import lombok.NonNull;
import org.apache.commons.collections4.EnumerationUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.rx.exception.InvalidException;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

import io.netty.util.internal.ThreadLocalRandom;

import static org.rx.core.Constants.NON_UNCHECKED;

/**
 * System.arraycopy();
 * Arrays.copyOf();
 */
public class Arrays extends ArrayUtils {
    public static <T> List<T> toList(Enumeration<T> enumeration) {
        return EnumerationUtils.toList(enumeration);
    }

    @SuppressWarnings(NON_UNCHECKED)
    public static <T> List<T> toList(@NonNull T one) {
        T[] arr = (T[]) Array.newInstance(one.getClass(), 1);
        arr[0] = one;
        return toList(arr);
    }

    @SafeVarargs
    public static <T> List<T> toList(T... items) {
        if (items == null) {
            return new ArrayList<>();
        }

        List<T> list = new ArrayList<>(items.length);
        Collections.addAll(list, items);
        return list;
    }

    public static int randomGet(int[] array) {
        if (isEmpty(array)) {
            throw new InvalidException("Array is empty");
        }

        return array[ThreadLocalRandom.current().nextInt(0, array.length)];
    }

    public static boolean equals(byte[] a, byte[] b) {
        return java.util.Arrays.equals(a, b);
    }
}
