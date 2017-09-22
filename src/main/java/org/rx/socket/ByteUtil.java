package org.rx.socket;

import static org.rx.Contract.require;

public class ByteUtil {
    //    byte[] data = s.getBytes();
    //    int i, skip = 0;
    //        for (i = 0; i < data.length; i++) {
    //        byte b = data[i];
    //        if (b == line) {
    //            skip = 1;
    //            break;
    //        }
    //        if (b == line2) {
    //            skip = 2;
    //            break;
    //        }
    //    }
    //        if (skip > 0) {
    //        System.out.println(new String(data, 0, i));
    //    }
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
