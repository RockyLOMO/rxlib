package org.rx.core;

import lombok.NonNull;
import org.apache.commons.collections4.EnumerationUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.rx.exception.InvalidException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

import io.netty.util.internal.ThreadLocalRandom;

/**
 * System.arraycopy();
 * Arrays.copyOf();
 */
public class Arrays extends ArrayUtils {
    public static <T> List<T> toList(Enumeration<T> enumeration) {
        return EnumerationUtils.toList(enumeration);
    }

    public static <T> List<T> toList(@NonNull T one) {
        List<T> list = new ArrayList<>(1);
        list.add(one);
        return list;
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

    public static int randomNext(int[] array) {
        if (isEmpty(array)) {
            throw new InvalidException("Empty array");
        }

        return array[ThreadLocalRandom.current().nextInt(0, array.length)];
    }

    public static <T> T randomNext(T[] array) {
        if (isEmpty(array)) {
            throw new InvalidException("Empty array");
        }

        return array[ThreadLocalRandom.current().nextInt(0, array.length)];
    }

    public static boolean equals(byte[] a, byte[] b) {
        return java.util.Arrays.equals(a, b);
    }
}
