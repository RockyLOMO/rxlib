package org.rx.io;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import lombok.SneakyThrows;
import org.rx.core.Constants;
import org.rx.core.Strings;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.security.SecureRandom;
import java.util.EnumSet;
import java.util.Set;

public class Bytes {
    static final SecureRandom RANDOM = new SecureRandom();

    //region ByteBuf
    public static String hexDump(ByteBuf buf) {
        return ByteBufUtil.prettyHexDump(buf);
    }

    public static ByteBuf wrap(byte[] buffer, int offset, int length) {
        return Unpooled.wrappedBuffer(buffer, offset, length);
    }

    public static ByteBuf wrap(ByteBuffer buffer) {
        return Unpooled.wrappedBuffer(buffer);
    }

    @SneakyThrows
    public static ByteBuf wrap(InputStream in) {
        return wrap(in, in.available());
    }

    @SneakyThrows
    public static ByteBuf wrap(InputStream in, int length) {
        ByteBuf buf = directBuffer(length);
        buf.writeBytes(in, length);
        return buf;
    }

    public static byte[] toBytes(ByteBuf buf) {
        return ByteBufUtil.getBytes(buf, buf.readerIndex(), buf.readableBytes(), false);
    }

    public static byte[] arrayBuffer() {
        return new byte[Constants.ARRAY_BUF_SIZE];
    }

    public static ByteBuf heapBuffer() {
        return heapBuffer(Constants.HEAP_BUF_SIZE);
    }

    public static ByteBuf heapBuffer(int initialCapacity) {
        return PooledByteBufAllocator.DEFAULT.heapBuffer(initialCapacity, Constants.MAX_HEAP_BUF_SIZE);
    }

    public static ByteBuf directBuffer() {
        return directBuffer(Constants.HEAP_BUF_SIZE);
    }

    public static ByteBuf directBuffer(int initialCapacity) {
        //AdaptiveByteBufAllocator.DEFAULT
        //UnpooledByteBufAllocator.DEFAULT
        return PooledByteBufAllocator.DEFAULT.directBuffer(initialCapacity);
    }

    public static void release(ByteBuf buf) {
        if (buf != null && buf.refCnt() > 0) {
            buf.release();
        }
    }

    //jdk11 --add-opens java.base/java.lang=ALL-UNNAMED
    public static void release(ByteBuffer buffer) {
        if (buffer == null || !buffer.isDirect() || buffer.capacity() == 0) {
            return;
        }

        invoke(invoke(viewed(buffer), "cleaner"), "clean");
    }

    private static Object invoke(final Object target, final String methodName, final Class<?>... args) {
        return AccessController.doPrivileged((PrivilegedAction<Object>) () -> {
            try {
                Method method;
                try {
                    method = target.getClass().getMethod(methodName, args);
                } catch (NoSuchMethodException e) {
                    method = target.getClass().getDeclaredMethod(methodName, args);
                }
                method.setAccessible(true);
                return method.invoke(target);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private static ByteBuffer viewed(ByteBuffer buffer) {
        String methodName = "viewedBuffer";
        Method[] methods = buffer.getClass().getMethods();
        for (Method method : methods) {
            if (Strings.hashEquals(method.getName(), "attachment")) {
                methodName = "attachment";
                break;
            }
        }

        ByteBuffer viewedBuffer = (ByteBuffer) invoke(buffer, methodName);
        if (viewedBuffer == null) {
            return buffer;
        } else {
            return viewed(viewedBuffer);
        }
    }

    public static int findText(ByteBuf byteBuf, String str) {
        byte[] text = str.getBytes(StandardCharsets.UTF_8);
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
    //endregion

    public static long wrap(int high, int low) {
        return (((long) high) << 32) | (low & 0xffffffffL);
    }

    public static int[] unwrap(long n) {
        return new int[]{(int) (n >> 32), (int) n};
    }

    public static int wrap(short high, short low) {
        return (high << 16) | (low & 0xffff);
    }

    public static short[] unwrap(int n) {
        return new short[]{(short) (n >> 16), (short) n};
    }

    public static int readInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 24) |
                ((bytes[offset + 1] & 0xFF) << 16) |
                ((bytes[offset + 2] & 0xFF) << 8) |
                (bytes[offset + 3] & 0xFF);
    }

    /**
     * Writes an int value to a byte array at the specified offset in big-endian order.
     */
    public static void writeInt(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) (value >> 24);
        bytes[offset + 1] = (byte) (value >> 16);
        bytes[offset + 2] = (byte) (value >> 8);
        bytes[offset + 3] = (byte) value;
    }

    public static long readLong(byte[] bytes, int offset) {
        return ((long) (bytes[offset] & 0xFF) << 56) |
                ((long) (bytes[offset + 1] & 0xFF) << 48) |
                ((long) (bytes[offset + 2] & 0xFF) << 40) |
                ((long) (bytes[offset + 3] & 0xFF) << 32) |
                ((long) (bytes[offset + 4] & 0xFF) << 24) |
                ((long) (bytes[offset + 5] & 0xFF) << 16) |
                ((long) (bytes[offset + 6] & 0xFF) << 8) |
                (bytes[offset + 7] & 0xFF);
    }

    public static void writeLong(byte[] bytes, int offset, long value) {
        bytes[offset] = (byte) (value >> 56);
        bytes[offset + 1] = (byte) (value >> 48);
        bytes[offset + 2] = (byte) (value >> 40);
        bytes[offset + 3] = (byte) (value >> 32);
        bytes[offset + 4] = (byte) (value >> 24);
        bytes[offset + 5] = (byte) (value >> 16);
        bytes[offset + 6] = (byte) (value >> 8);
        bytes[offset + 7] = (byte) value;
    }

    //region byte[]
    public static byte[] toBytes(int val) {
        byte[] buffer = new byte[Integer.BYTES];
        writeInt(buffer, 0, val);
        return buffer;
    }

    public static byte[] toBytes(long val) {
        byte[] buffer = new byte[Long.BYTES];
        writeLong(buffer, 0, val);
        return buffer;
    }

    public static byte[] toBytes(InputStream in) {
        return toBytes(in, IOStream.NON_READ_FULLY);
    }

    public static byte[] toBytes(InputStream in, int length) {
        try (MemoryStream s = new MemoryStream()) {
            s.write(in, length);
            return s.toArray();
        }
    }

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

    public static void reverse(byte[] array, int offset, int length) {
        for (int i = offset; i < length / 2; i++) {
            byte temp = array[i];
            array[i] = array[array.length - i - 1];
            array[array.length - i - 1] = temp;
        }
    }

    public static byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        RANDOM.nextBytes(bytes);
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
