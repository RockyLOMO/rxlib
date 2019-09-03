package org.rx.socks;

import java.nio.charset.StandardCharsets;

import static org.rx.core.Contract.require;

public class Bytes {
    public static String readLine(byte[] buffer) {
        require(buffer);

        return readLine(buffer, 0, buffer.length);
    }

    public static String readLine(byte[] buffer, int offset, int count) {
        require(buffer);

        final byte line = '\n', line2 = '\r';
        for (int i = offset; i < Math.min(count, buffer.length); i++) {
            byte b = buffer[i];
            if (b == line || b == line2) {
                return toString(buffer, offset, i);
            }
        }
        return null;
    }

    public static byte[] getBytes(String str) {
        require(str);

        return str.getBytes(StandardCharsets.UTF_8);
    }

    public static String toString(byte[] buffer, int offset, int count) {
        return new String(buffer, offset, count, StandardCharsets.UTF_8);
    }

    public static void reverse(byte[] array, int offset, int length) {
        require(array);

        for (int i = offset; i < length / 2; i++) {
            byte temp = array[i];
            array[i] = array[array.length - i - 1];
            array[array.length - i - 1] = temp;
        }
    }

    public static void getBytes(int value, byte[] buffer, int offset) {
        require(buffer);

        buffer[offset] = (byte) (0xff & (value >> 24));
        buffer[offset + 1] = (byte) (0xff & (value >> 16));
        buffer[offset + 2] = (byte) (0xff & (value >> 8));
        buffer[offset + 3] = (byte) (0xff & value);
    }

    public static int toInt(byte[] buffer, int offset) {
        require(buffer);

        return ((buffer[offset] & 0xff) << 24) | ((buffer[offset + 1] & 0xff) << 16)
                | ((buffer[offset + 2] & 0xff) << 8) | (buffer[offset + 3] & 0xff);
    }

    public static void getBytes(long value, byte[] buffer, int offset) {
        require(buffer);

        buffer[offset] = (byte) (0xff & (value >> 56));
        buffer[offset + 1] = (byte) (0xff & (value >> 48));
        buffer[offset + 2] = (byte) (0xff & (value >> 40));
        buffer[offset + 3] = (byte) (0xff & (value >> 32));
        buffer[offset + 4] = (byte) (0xff & (value >> 24));
        buffer[offset + 5] = (byte) (0xff & (value >> 16));
        buffer[offset + 6] = (byte) (0xff & (value >> 8));
        buffer[offset + 7] = (byte) (0xff & value);
    }

    public static long toLong(byte[] buffer, int offset) {
        require(buffer);

        return ((long) (buffer[offset] & 0xff) << 56) | ((long) (buffer[offset + 1] & 0xff) << 48)
                | ((long) (buffer[offset + 2] & 0xff) << 40) | ((long) (buffer[offset + 3] & 0xff) << 32)
                | ((long) (buffer[offset + 4] & 0xff) << 24) | ((long) (buffer[offset + 5] & 0xff) << 16)
                | ((long) (buffer[offset + 6] & 0xff) << 8) | ((long) (buffer[offset + 7] & 0xff));
    }
}
