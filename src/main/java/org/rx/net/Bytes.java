package org.rx.net;

import io.netty.buffer.*;
import lombok.SneakyThrows;

import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.EnumSet;
import java.util.Set;

public class Bytes {
    public static final int IntByteSize = 4;
    public static final int LongByteSize = 8;

    //region value
    public static byte[] getBytes(int val) {
        byte[] buffer = new byte[IntByteSize];
        getBytes(val, buffer, 0);
        return buffer;
    }

    public static void getBytes(int val, byte[] buffer, int offset) {
        ByteBuf buf = Unpooled.wrappedBuffer(buffer, offset, IntByteSize);
        buf.writerIndex(0);
        buf.writeInt(val);
    }

    public static int getInt(byte[] buffer, int offset) {
        ByteBuf buf = Unpooled.wrappedBuffer(buffer, offset, IntByteSize);
        return buf.readInt();
    }

    public static byte[] getBytes(long val) {
        byte[] buffer = new byte[LongByteSize];
        getBytes(val, buffer, 0);
        return buffer;
    }

    public static void getBytes(long val, byte[] buffer, int offset) {
        ByteBuf buf = Unpooled.wrappedBuffer(buffer, offset, LongByteSize);
        buf.writerIndex(0);
        buf.writeLong(val);
    }

    public static long getLong(byte[] buffer, int offset) {
        ByteBuf buf = Unpooled.wrappedBuffer(buffer, offset, LongByteSize);
        return buf.readLong();
    }
    //endregion

    @SneakyThrows
    public static ByteBuf copyInputStream(InputStream in) {
        return copyInputStream(in, in.available());
    }

    @SneakyThrows
    public static ByteBuf copyInputStream(InputStream in, int length) {
        ByteBuf buf = directBuffer(length);
        buf.writeBytes(in, length);
        return buf;
    }

    public static ByteBuf directBuffer(int initialCapacity) {
        return directBuffer(initialCapacity, false);
    }

    public static ByteBuf directBuffer(int initialCapacity, boolean unpool) {
        ByteBufAllocator allocator = unpool ? UnpooledByteBufAllocator.DEFAULT : PooledByteBufAllocator.DEFAULT;
        return allocator.directBuffer(initialCapacity);
    }

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
        return readLine(buffer, 0, buffer.length);
    }

    public static String readLine(byte[] buffer, int offset, int count) {
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
        return str.getBytes(StandardCharsets.UTF_8);
    }

    public static String toString(byte[] buffer, int offset, int count) {
        return new String(buffer, offset, count, StandardCharsets.UTF_8);
    }

    public static void reverse(byte[] array, int offset, int length) {
        for (int i = offset; i < length / 2; i++) {
            byte temp = array[i];
            array[i] = array[array.length - i - 1];
            array[array.length - i - 1] = temp;
        }
    }
}
