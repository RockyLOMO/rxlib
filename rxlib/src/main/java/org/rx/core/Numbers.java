package org.rx.core;

import org.apache.commons.lang3.math.NumberUtils;

import static org.rx.core.Constants.PERCENT;

public class Numbers extends NumberUtils {
    public static boolean isEmpty(Number num) {
        return num == null || num.intValue() == INTEGER_ZERO;
    }

    public static <T extends Number> T isEmpty(T a, T b) {
        return !isEmpty(a) ? a : b;
    }

    public static long longValue(Number num) {
        return isEmpty(num) ? LONG_ZERO : num.longValue();
    }

    public static double doubleValue(Number num) {
        return isEmpty(num) ? DOUBLE_ZERO : num.doubleValue();
    }

    public static int toPercent(double val) {
        return (int) Math.ceil(val * PERCENT);
    }

    //Whether to include decimals
    public static boolean hasPrecision(double n) {
        return n % 1 == 0;
    }
}
