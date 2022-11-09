package org.rx.io;

import io.netty.buffer.ByteBuf;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.annotation.ErrorCode;
import org.rx.core.StringBuilder;
import org.rx.core.*;
import org.rx.exception.ApplicationException;

import java.io.*;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Objects;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;

import static org.rx.core.Extends.*;

@Slf4j
public abstract class IOStream<TI extends InputStream, TO extends OutputStream> extends Disposable implements Closeable, Flushable, Extends {
    private static final long serialVersionUID = 3204673656139586437L;
    public static final int NON_READ_FULLY = -1;

    public static IOStream<?, ?> wrap(String filePath) {
        return wrap(new File(filePath));
    }

    public static IOStream<?, ?> wrap(@NonNull File file) {
        return new FileStream(file);
    }

    public static IOStream<?, ?> wrap(String name, byte[] data) {
        HybridStream stream = new HybridStream();
        stream.setName(ifNull(name, Strings.EMPTY));
        stream.write(data);
        return stream.rewind();
    }

    public static IOStream<?, ?> wrap(String name, InputStream in) {
        HybridStream stream = new HybridStream();
        stream.setName(ifNull(name, Strings.EMPTY));
        stream.write(in);
        return stream.rewind();
    }

    @SneakyThrows
    public static long copy(@NonNull InputStream in, long length, @NonNull OutputStream out) {
        byte[] buffer = Bytes.arrayBuffer();
        boolean readFully = length != NON_READ_FULLY;
        long copyLen = 0;
        int read;
        while ((!readFully || copyLen < length) && (read = in.read(buffer, 0, buffer.length)) != Constants.IO_EOF) {
            out.write(buffer, 0, read);
            copyLen += read;
        }
        out.flush();
        return copyLen;
    }

    public static long checksum(byte[] bytes) {
        CRC32 crc32 = new CRC32();
        crc32.update(bytes, 0, bytes.length);
        return crc32.getValue();
    }

    public static long checksum(InputStream stream) {
        return checksum(stream, 1024);
    }

    @SneakyThrows
    public static long checksum(InputStream stream, int bufferSize) {
        CheckedInputStream checkedInputStream = new CheckedInputStream(stream, new CRC32());
        byte[] buffer = new byte[bufferSize];
        while (checkedInputStream.read(buffer, 0, buffer.length) >= 0) {
        }
        return checkedInputStream.getChecksum().getValue();
    }

    @SneakyThrows
    public static String readString(@NonNull InputStream in, Charset charset) {
        if (charset == null) {
            charset = StandardCharsets.UTF_8;
        }

        StringBuilder result = new StringBuilder();
        byte[] buffer = Bytes.arrayBuffer();
        int read;
        while ((read = in.read(buffer)) > 0) {
            result.append(new String(buffer, 0, read, charset));
        }
        return result.toString();
    }

    @SneakyThrows
    public static void writeString(@NonNull OutputStream out, @NonNull String value, Charset charset) {
        if (charset == null) {
            charset = StandardCharsets.UTF_8;
        }

        out.write(value.getBytes(charset));
    }

    static int safeRemaining(long remaining) {
        return remaining >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) remaining;
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

    private transient TI reader;
    private transient TO writer;

    public TI getReader() {
        if (reader == null) {
            reader = Objects.requireNonNull(initReader());
        }
        return reader;
    }

    public TO getWriter() {
        if (writer == null) {
            writer = Objects.requireNonNull(initWriter());
        }
        return writer;
    }

    protected abstract TI initReader();

    protected abstract TO initWriter();

    public abstract String getName();

    public boolean canWrite() {
        return !isClosed();
    }

    public boolean canSeek() {
        return false;
    }

    @ErrorCode
    public long getPosition() {
        throw new ApplicationException(values());
    }

    @ErrorCode
    public void setPosition(long position) {
        throw new ApplicationException(values());
    }

    @ErrorCode
    public long getLength() {
        throw new ApplicationException(values(this.getClass().getSimpleName()));
    }

    @SneakyThrows
    @Override
    protected void freeObjects() {
        flush();
        tryClose(getWriter());
        tryClose(getReader());
    }

    @Override
    public void close() {
        quietly(super::close);
    }

    @SneakyThrows
    public long available() {
        if (isClosed()) {
            return 0;
        }

        return getReader().available();
    }

    @SneakyThrows
    public int read() {
        checkNotClosed();

        return getReader().read();
    }

    public int read(@NonNull byte[] buffer) {
        checkNotClosed();

        return read(buffer, 0, buffer.length);
    }

    @SneakyThrows
    public int read(@NonNull byte[] buffer, int offset, int length) {
        checkNotClosed();
        require(offset, offset >= 0);

        return getReader().read(buffer, offset, length);
    }

    public long read(IOStream<?, ?> stream) {
        return read(stream, NON_READ_FULLY);
    }

    public long read(@NonNull IOStream<?, ?> stream, long length) {
        return read(stream.getWriter(), length);
    }

    public long read(OutputStream out) {
        return read(out, NON_READ_FULLY);
    }

    public long read(OutputStream out, long length) {
        checkNotClosed();

        return copy(getReader(), length, out);
    }

    public int read(ByteBuf dst) {
        //available() may be not right
        int total = 0, read;
        while ((read = read(dst, Constants.HEAP_BUF_SIZE)) > 0) {
            total += read;
        }
        return total;
    }

    @SneakyThrows
    public int read(ByteBuf dst, int length) {
        return dst.writeBytes(getReader(), length);
    }

    @SneakyThrows
    public short readShort() {
        int ch1 = read();
        int ch2 = read();
        if ((ch1 | ch2) < 0) {
            throw new EOFException();
        }
        return (short) ((ch1 << 8) + (ch2 << 0));
    }

    @SneakyThrows
    public int readInt() {
        int ch1 = read();
        int ch2 = read();
        int ch3 = read();
        int ch4 = read();
        if ((ch1 | ch2 | ch3 | ch4) < 0) {
            throw new EOFException();
        }
        return ((ch1 << 24) + (ch2 << 16) + (ch3 << 8) + (ch4 << 0));
    }

    @SneakyThrows
    public void write(int b) {
        checkNotClosed();

        getWriter().write(b);
    }

    public void write(@NonNull byte[] buffer) {
        checkNotClosed();

        write(buffer, 0, buffer.length);
    }

    @SneakyThrows
    public void write(@NonNull byte[] buffer, int offset, int length) {
        checkNotClosed();
        require(offset, offset >= 0);

        getWriter().write(buffer, offset, length);
    }

    public long write(IOStream<?, ?> stream) {
        return write(stream, NON_READ_FULLY);
    }

    public long write(@NonNull IOStream<?, ?> stream, long length) {
        return write(stream.getReader(), length);
    }

    public long write(InputStream in) {
        return write(in, NON_READ_FULLY);
    }

    public long write(InputStream in, long length) {
        checkNotClosed();

        return copy(in, length, getWriter());
    }

    public void write(ByteBuf src) {
        write(src, src.readableBytes());
    }

    @SneakyThrows
    public void write(ByteBuf src, int length) {
        src.readBytes(getWriter(), length);
    }

    public void writeShort(short n) {
        write((n >>> 8) & 0xFF);
        write((n >>> 0) & 0xFF);
    }

    public void writeInt(int n) {
        write((n >>> 24) & 0xFF);
        write((n >>> 16) & 0xFF);
        write((n >>> 8) & 0xFF);
        write((n >>> 0) & 0xFF);
    }

    public void writeString(String str) {
        writeString(str, StandardCharsets.UTF_8);
    }

    public void writeString(String str, Charset charset) {
        write(str.getBytes(charset));
    }

    @SneakyThrows
    @Override
    public void flush() {
        checkNotClosed();

        getWriter().flush();
    }

    public byte[] toArray() {
        checkNotClosed();

        long pos = getPosition();
        setPosition(0);
        byte[] data = new byte[(int) getLength()];
        read(data);
        setPosition(pos);
        return data;
    }

    public final IOStream<TI, TO> rewind() {
        setPosition(0);
        return this;
    }
}
