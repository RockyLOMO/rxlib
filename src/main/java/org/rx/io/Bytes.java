package org.rx.io;

import io.netty.buffer.*;
import lombok.SneakyThrows;
import org.rx.core.Constants;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.DecimalFormat;
import java.util.EnumSet;
import java.util.Set;

public class Bytes {
    public static String hexDump(ByteBuf buf) {
        return ByteBufUtil.prettyHexDump(buf);
    }

    public static ByteBuf wrap(byte[] buffer, int offset, int length) {
        return Unpooled.wrappedBuffer(buffer, offset, length);
    }

    public static ByteBuf wrap(ByteBuffer buffer) {
        return Unpooled.wrappedBuffer(buffer);
    }

    public static byte[] getBytes(ByteBuf buf) {
        return ByteBufUtil.getBytes(buf, buf.readerIndex(), buf.readableBytes(), false);
    }

    public static byte[] arrayBuffer() {
        return new byte[Constants.HEAP_BUF_SIZE];
    }

    public static ByteBuf heapBuffer() {
        return heapBuffer(Constants.HEAP_BUF_SIZE, false);
    }

    public static ByteBuf heapBuffer(int initialCapacity, boolean unpool) {
        ByteBufAllocator allocator = unpool ? UnpooledByteBufAllocator.DEFAULT : PooledByteBufAllocator.DEFAULT;
        return allocator.heapBuffer(initialCapacity, Constants.MAX_HEAP_BUF_SIZE);
    }

    public static ByteBuf directBuffer() {
        return directBuffer(BufferedRandomAccessFile.BufSize.SMALL_DATA.value);
    }

    public static ByteBuf directBuffer(int initialCapacity) {
        return PooledByteBufAllocator.DEFAULT.directBuffer(initialCapacity);
    }

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

    public static long wrap(int a, int b) {
        return (((long) a) << 32) | (b & 0xffffffffL);
    }

    public static int[] unwrap(long l) {
        return new int[]{(int) (l >> 32), (int) l};
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

    //region heap
    public static String readLine(byte[] buffer) {
        return readLine(buffer, 0, buffer.length);
    }

    public static String readLine(byte[] buffer, int offset, int length) {
        final byte line = '\n', line2 = '\r';
        for (int i = offset; i < Math.min(length, buffer.length); i++) {
            byte b = buffer[i];
            if (b == line || b == line2) {
                return toString(buffer, offset, i);
            }
        }
        return null;
    }

    public static String toString(ByteBuffer buffer) {
        return wrap(buffer).toString(StandardCharsets.UTF_8);
    }

    public static String toString(byte[] buffer, int offset, int length) {
        return new String(buffer, offset, length, StandardCharsets.UTF_8);
    }

    public static byte[] getBytes(int val) {
        byte[] buffer = new byte[Integer.BYTES];
        getBytes(val, buffer, 0);
        return buffer;
    }

    public static void getBytes(int val, byte[] buffer, int offset) {
        ByteBuf buf = Unpooled.wrappedBuffer(buffer, offset, Integer.BYTES);
        buf.writerIndex(0);
        buf.writeInt(val);
    }

    public static int getInt(byte[] buffer, int offset) {
        ByteBuf buf = Unpooled.wrappedBuffer(buffer, offset, Integer.BYTES);
        return buf.readInt();
    }

    public static byte[] getBytes(long val) {
        byte[] buffer = new byte[Long.BYTES];
        getBytes(val, buffer, 0);
        return buffer;
    }

    public static void getBytes(long val, byte[] buffer, int offset) {
        ByteBuf buf = Unpooled.wrappedBuffer(buffer, offset, Long.BYTES);
        buf.writerIndex(0);
        buf.writeLong(val);
    }

    public static long getLong(byte[] buffer, int offset) {
        ByteBuf buf = Unpooled.wrappedBuffer(buffer, offset, Long.BYTES);
        return buf.readLong();
    }

    public static void reverse(byte[] array, int offset, int length) {
        for (int i = offset; i < length / 2; i++) {
            byte temp = array[i];
            array[i] = array[array.length - i - 1];
            array[array.length - i - 1] = temp;
        }
    }

    public static byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    //https://stackoverflow.com/a/5599842/1253611
    public static strictfp String readableByteSize(double val) {
        if (val < Constants.KB) {
            return String.format("%.0fB", val);
        }
        if (val < Constants.MB) {
            return String.format("%.1fKB", val / Constants.KB);
        }
        if (val < Constants.GB) {
            return String.format("%.1fMB", val / Constants.MB);
        }
        if (val < Constants.TB) {
            return String.format("%.1fGB", val / Constants.GB);
        }
        return String.format("%.1fTB", val / Constants.TB);

//        int unit = si ? 1000 : 1024;
//        long absBytes = bytes == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(bytes);
//        if (absBytes < unit) return bytes + " B";
//        int exp = (int) (Math.log(absBytes) / Math.log(unit));
//        long th = (long) Math.ceil(Math.pow(unit, exp) * (unit - 0.05));
//        if (exp < 6 && absBytes >= th - ((th & 0xFFF) == 0xD00 ? 51 : 0)) exp++;
//        String pre = (si ? "kMGTPE" : "KMGTPE").charAt(exp - 1) + (si ? "" : "i");
//        if (exp > 4) {
//            bytes /= unit;
//            exp -= 1;
//        }
//        return String.format("%.1f %sB", bytes / Math.pow(unit, exp), pre);
    }

    public static <E extends Enum<E>> int toEnumVector(Set<E> set) {
        int vector = 0;
        for (E e : set) {
            if (e.ordinal() >= Integer.SIZE) {
                throw new IllegalArgumentException("The enum set is too large to fit in a bit vector: " + set);
            }
            vector |= 1L << e.ordinal();
        }
        return vector;
    }

    public static <E extends Enum<E>> EnumSet<E> fromEnumVector(Class<E> enumClass, int vector) {
        EnumSet<E> set = EnumSet.noneOf(enumClass);
        for (E e : enumClass.getEnumConstants()) {
            int mask = 1 << e.ordinal();
            if ((mask & vector) != 0) {
                set.add(e);
            }
        }
        return set;
    }
    //endregion
}
