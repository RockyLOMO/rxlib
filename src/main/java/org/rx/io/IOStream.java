package org.rx.io;

import io.netty.buffer.ByteBuf;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Disposable;
import org.rx.annotation.ErrorCode;
import org.rx.core.StringBuilder;
import org.rx.core.exception.ApplicationException;

import java.io.*;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Objects;

import static org.rx.core.App.*;

@Slf4j
public abstract class IOStream<TI extends InputStream, TO extends OutputStream> extends Disposable implements Closeable, Flushable, Serializable {
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
        stream.setName(name);
        stream.write(data);
        stream.setPosition(0L);
        return stream;
    }

    public static IOStream<?, ?> wrap(String name, InputStream in) {
        HybridStream stream = new HybridStream();
        stream.setName(name);
        stream.write(in);
        stream.setPosition(0L);
        return stream;
    }

    @SneakyThrows
    public static long copy(@NonNull InputStream in, long length, @NonNull OutputStream out) {
        byte[] buffer = Bytes.arrayBuffer();
        boolean readFully = length != NON_READ_FULLY;
        long copyLen = 0;
        int read;
        while ((!readFully || copyLen < length) && (read = in.read(buffer, 0, buffer.length)) != -1) {
            out.write(buffer, 0, read);
            copyLen += read;
        }
        out.flush();
        return copyLen;
    }

    @SneakyThrows
    public static String readString(@NonNull InputStream in, @NonNull Charset charset) {
        StringBuilder result = new StringBuilder();
        byte[] buffer = Bytes.arrayBuffer();
        int read;
        while ((read = in.read(buffer)) > 0) {
            result.append(new String(buffer, 0, read, charset));
        }
        return result.toString();
    }

    @SneakyThrows
    public static void writeString(@NonNull OutputStream out, @NonNull String value, @NonNull Charset charset) {
        out.write(value.getBytes(charset));
    }

    static int safeRemaining(long remaining) {
        return remaining >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) remaining;
    }

    //jdk11 --add-opens java.base/java.lang=ALL-UNNAMED
    public static void release(@NonNull ByteBuffer buffer) {
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
            if (method.getName().equals("attachment")) {
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

    public boolean canRead() {
        return !isClosed() && available() > 0;
    }

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
        checkNotClosed();

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
        return read(dst, dst.writableBytes());
    }

    @SneakyThrows
    public int read(ByteBuf dst, int length) {
        return dst.writeBytes(getReader(), length);
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

    @SneakyThrows
    @Override
    public void flush() {
        checkNotClosed();

        getWriter().flush();
    }

    public synchronized byte[] toArray() {
        checkNotClosed();

        long pos = getPosition();
        setPosition(0);
        byte[] data = new byte[(int) (getLength() - pos)];
        read(data);
        setPosition(pos);
        return data;
    }
}
