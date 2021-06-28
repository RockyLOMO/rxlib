package org.rx.io;

import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.rx.bean.RxConfig;
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
@AllArgsConstructor
public abstract class IOStream<TI extends InputStream, TO extends OutputStream> extends Disposable implements Closeable, Flushable, Serializable {
    private static final long serialVersionUID = 3204673656139586437L;

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
        copyTo(in, stream.getWriter());
        stream.setPosition(0L);
        return stream;
    }

    public static void copyTo(InputStream in, OutputStream out) {
        copyTo(in, -1, out);
    }

    @SneakyThrows
    public static void copyTo(@NonNull InputStream in, long count, @NonNull OutputStream out) {
        byte[] buffer = Bytes.arrayBuffer();
        int read;
        boolean fixCount = count != -1;
        while ((!fixCount || count > 0) && (read = in.read(buffer, 0, buffer.length)) != -1) {
            out.write(buffer, 0, read);
            if (fixCount) {
                count -= read;
            }
        }
        out.flush();
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

    public static <T extends Serializable> IOStream<?, ?> serialize(T obj) {
        return serialize(obj, RxConfig.MAX_HEAP_BUF_SIZE, null);
    }

    @SneakyThrows
    public static <T extends Serializable> HybridStream serialize(@NonNull T obj, int maxMemorySize, String tempFilePath) {
        HybridStream stream = new HybridStream(maxMemorySize, tempFilePath);
        ObjectOutputStream out = new ObjectOutputStream(stream.getWriter());
        out.writeObject(obj);  //close 会关闭stream
        out.flush();
        stream.setPosition(0L);
        return stream;
    }

    public static <T extends Serializable> T deserialize(IOStream<?, ?> stream) {
        return deserialize(stream, false);
    }

    @SneakyThrows
    public static <T extends Serializable> T deserialize(@NonNull IOStream<?, ?> stream, boolean leveClose) {
        try {
            ObjectInputStream in = new ObjectInputStream(stream.getReader());
            return (T) in.readObject();
        } finally {
            if (leveClose) {
                stream.close();
            }
        }
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

    @Setter(AccessLevel.PROTECTED)
    protected transient TI reader;
    @Setter(AccessLevel.PROTECTED)
    protected transient TO writer;

    public abstract String getName();

    public TI getReader() {
        Objects.requireNonNull(reader);
        return reader;
    }

    public TO getWriter() {
        Objects.requireNonNull(writer);
        return writer;
    }

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
        quietly(this::flush);
        tryClose(writer);
        tryClose(reader);
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

    public int read(@NonNull byte[] data) {
        checkNotClosed();

        return read(data, 0, data.length);
    }

    @SneakyThrows
    public int read(@NonNull byte[] buffer, int offset, int count) {
        checkNotClosed();
        require(offset, offset >= 0);

        return getReader().read(buffer, offset, count);
    }

    @SneakyThrows
    public void write(int b) {
        checkNotClosed();

        getWriter().write(b);
    }

    public void write(@NonNull byte[] data) {
        checkNotClosed();

        write(data, 0, data.length);
    }

    @SneakyThrows
    public void write(@NonNull byte[] buffer, int offset, int count) {
        checkNotClosed();
        require(offset, offset >= 0);

        getWriter().write(buffer, offset, count);
    }

    public void write(@NonNull IOStream<?, ?> in, long count) {
        write(in.getReader(), count);
    }

    public void write(InputStream in, long count) {
        checkNotClosed();

        copyTo(in, count, getWriter());
    }

    @SneakyThrows
    @Override
    public void flush() {
        checkNotClosed();

        getWriter().flush();
    }

    public void copyFrom(@NonNull IOStream<?, ?> in) {
        copyFrom(in.getReader());
    }

    public void copyFrom(@NonNull InputStream in) {
        doCopy(in, getWriter());
    }

    public void copyTo(@NonNull IOStream<?, ?> out) {
        copyTo(out.getWriter());
    }

    public void copyTo(@NonNull OutputStream out) {
        doCopy(getReader(), out);
    }

    //重置position
    private synchronized void doCopy(InputStream in, OutputStream out) {
        checkNotClosed();

        if (!canSeek()) {
            log.warn("{} can't seek, reset position ignore", this.getClass().getName());
            copyTo(in, out);
            return;
        }
        long pos = getPosition();
        setPosition(0L);
        copyTo(in, out);
        setPosition(pos);
    }

    public synchronized byte[] toArray() {
        checkNotClosed();

        long pos = getPosition();
        byte[] data = new byte[(int) (getLength() - pos)];
        read(data);
        setPosition(pos);
        return data;
    }
}
