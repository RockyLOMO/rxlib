package org.rx.util;

/**
 * Created by wangxiaoming on 2016/8/24.
 */
public class ByteUtil {
    public static void reverse(byte[] array) {
        reverse(array, 0, array.length);
    }

    //Collections.reverse()
    public static void reverse(byte[] array, int offset, int length) {
        for (int i = offset; i < length / 2; i++) {
            byte temp = array[i];
            array[i] = array[array.length - i - 1];
            array[array.length - i - 1] = temp;
        }
    }

    //ByteBuffer
    public static void getBytes(int value, byte[] array, int offset) {
        array[offset] = (byte) (0xff & (value >> 24));
        array[offset + 1] = (byte) (0xff & (value >> 16));
        array[offset + 2] = (byte) (0xff & (value >> 8));
        array[offset + 3] = (byte) (0xff & value);
    }

    public static int toInt(byte[] array, int offset) {
        return ((array[offset] & 0xff) << 24) |
                ((array[offset + 1] & 0xff) << 16) |
                ((array[offset + 2] & 0xff) << 8) |
                (array[offset + 3] & 0xff);
    }

    public static byte[] getBytes(long value) {
        byte[] array = new byte[8];
        for (int i = 7; i >= 0; i--) {
            array[7 - i] = (byte) (value >> i * 8);
        }
        return array;
    }

    public static void getBytes(long value, byte[] array, int offset) {
        array[offset] = (byte) (0xff & (value >> 56));
        array[offset + 1] = (byte) (0xff & (value >> 48));
        array[offset + 2] = (byte) (0xff & (value >> 40));
        array[offset + 3] = (byte) (0xff & (value >> 32));
        array[offset + 4] = (byte) (0xff & (value >> 24));
        array[offset + 5] = (byte) (0xff & (value >> 16));
        array[offset + 6] = (byte) (0xff & (value >> 8));
        array[offset + 7] = (byte) (0xff & value);
    }

    public static long toLong(byte[] array, int offset) {
        return ((long) (array[offset] & 0xff) << 56) |
                ((long) (array[offset + 1] & 0xff) << 48) |
                ((long) (array[offset + 2] & 0xff) << 40) |
                ((long) (array[offset + 3] & 0xff) << 32) |
                ((long) (array[offset + 4] & 0xff) << 24) |
                ((long) (array[offset + 5] & 0xff) << 16) |
                ((long) (array[offset + 6] & 0xff) << 8) |
                ((long) (array[offset + 7] & 0xff));
    }
}
