package org.rx.core;

import org.apache.commons.lang3.math.NumberUtils;

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

    public static strictfp String readableByteCount(long bytes) {
        return readableByteCount(bytes, false);
    }

    public static strictfp String readableByteCount(long bytes, boolean si) {
        int unit = si ? 1000 : 1024;
        long absBytes = bytes == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(bytes);
        if (absBytes < unit) return bytes + " B";
        int exp = (int) (Math.log(absBytes) / Math.log(unit));
        long th = (long) Math.ceil(Math.pow(unit, exp) * (unit - 0.05));
        if (exp < 6 && absBytes >= th - ((th & 0xFFF) == 0xD00 ? 51 : 0)) exp++;
        String pre = (si ? "kMGTPE" : "KMGTPE").charAt(exp - 1) + (si ? "" : "i");
        if (exp > 4) {
            bytes /= unit;
            exp -= 1;
        }
        return String.format("%.1f %sB", bytes / Math.pow(unit, exp), pre);
    }
}
