package org.rx.net;

import io.netty.buffer.ByteBuf;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.EnumSet;
import java.util.Set;

import static org.rx.core.App.require;

public class Bytes {
    public static <E extends Enum<E>> EnumSet<E> toEnumSet(Class<E> enumClass, long vector) {
        EnumSet<E> set = EnumSet.noneOf(enumClass);
        for (E e : enumClass.getEnumConstants()) {
            final long mask = 1 << e.ordinal();
            if ((mask & vector) != 0) {
                set.add(e);
            }
        }
        return set;
    }

    public static <E extends Enum<E>> int toInt(Set<E> set) {
        int vector = 0;
        for (E e : set) {
            if (e.ordinal() >= Integer.SIZE) {
                throw new IllegalArgumentException("The enum set is too large to fit in a bit vector: " + set);
            }
            vector |= 1L << e.ordinal();
        }
        return vector;
    }

    public static int findText(ByteBuf byteBuf, String str) {
        byte[] text = str.getBytes();
        int matchIndex = 0;
        for (int i = byteBuf.readerIndex(); i < byteBuf.readableBytes(); i++) {
            for (int j = matchIndex; j < text.length; j++) {
                if (byteBuf.getByte(i) == text[j]) {
                    matchIndex = j + 1;
                    if (matchIndex == text.length) {
                        return i;
                    }
                } else {
                    matchIndex = 0;
                }
                break;
            }
        }
        return -1;
    }

    public static ByteBuf insertText(ByteBuf byteBuf, int index, String str) {
        return insertText(byteBuf, index, str, Charset.defaultCharset());
    }

    public static ByteBuf insertText(ByteBuf byteBuf, int index, String str, Charset charset) {
        byte[] begin = new byte[index + 1];
        byte[] end = new byte[byteBuf.readableBytes() - begin.length];
        byteBuf.readBytes(begin);
        byteBuf.readBytes(end);
        byteBuf.writeBytes(begin);
        byteBuf.writeBytes(str.getBytes(charset));
        byteBuf.writeBytes(end);
        return byteBuf;
    }

    public static String dumpBytes(byte[] a) {
        StringBuilder sb = new StringBuilder(a.length * 2);
        for (byte b : a)
            sb.append(String.format("%x", b & 0xff));
        return sb.toString();
    }

    public static byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

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
}
