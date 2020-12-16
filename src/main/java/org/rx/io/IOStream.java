package org.rx.io;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.App;
import org.rx.core.Disposable;
import org.rx.annotation.ErrorCode;
import org.rx.core.Contract;
import org.rx.core.StringBuilder;
import org.rx.core.exception.ApplicationException;

import java.io.*;

import static org.rx.core.Contract.*;

@Slf4j
@AllArgsConstructor
public abstract class IOStream<TI extends InputStream, TO extends OutputStream> extends Disposable implements Closeable, Flushable, Serializable {
    private static final long serialVersionUID = 3204673656139586437L;

    public static IOStream<?, ?> wrap(String filePath) {
        return wrap(new File(filePath));
    }

    public static IOStream<?, ?> wrap(File file) {
        require(file);

        return new FileStream(file);
    }

    public static IOStream<?, ?> wrap(String name, byte[] data) {
        require(data);

        HybridStream stream = new HybridStream();
        stream.setName(name);
        stream.write(data);
        stream.setPosition(0L);
        return stream;
    }

    public static String readString(InputStream stream) {
        return readString(stream, Contract.UTF_8);
    }

    @SneakyThrows
    public static String readString(InputStream in, String charset) {
        require(in, charset);

        StringBuilder result = new StringBuilder();
        byte[] buffer = createBuffer();
        int read;
        while ((read = in.read(buffer)) > 0) {
            result.append(new String(buffer, 0, read, charset));
        }
        return result.toString();
    }

    public static void writeString(OutputStream out, String value) {
        writeString(out, value, Contract.UTF_8);
    }

    @SneakyThrows
    public static void writeString(OutputStream out, String value, String charset) {
        require(out, charset);

        byte[] data = value.getBytes(charset);
        out.write(data);
    }

    public static void copyTo(InputStream in, OutputStream out) {
        copyTo(in, -1, out);
    }

    @SneakyThrows
    public static void copyTo(InputStream in, long count, OutputStream out) {
        require(in, out);

        byte[] buffer = createBuffer();
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

    public static byte[] createBuffer() {
        return new byte[App.getConfig().getBufferSize()];
    }

    public static byte[] toBytes(IOStream<?, ?> stream) {
        long pos = stream.getPosition();
        byte[] data = new byte[(int) (stream.getLength() - pos)];
        stream.read(data);
        stream.setPosition(pos);
        return data;
    }

    @SneakyThrows
    public static <T extends Serializable> IOStream<?, ?> serialize(T obj) {
        require(obj);

        HybridStream stream = new HybridStream();
        ObjectOutputStream out = new ObjectOutputStream(stream.getWriter());
        out.writeObject(obj);  //close 会关闭stream
        out.flush();
        stream.setPosition(0L);
        return stream;
    }

    @SneakyThrows
    public static <T extends Serializable> T deserialize(IOStream<?, ?> stream) {
        require(stream);

        ObjectInputStream in = new ObjectInputStream(stream.getReader());
        return (T) in.readObject();
    }

    @Setter(AccessLevel.PROTECTED)
    protected transient TI reader;
    @Setter(AccessLevel.PROTECTED)
    protected transient TO writer;

    public abstract String getName();

    public TI getReader() {
        require(reader);
        return reader;
    }

    public TO getWriter() {
        require(writer);
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

        if (writer != null) {
            writer.close();
        }
        if (reader != null) {
            reader.close();
        }
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

    public int read(byte[] data) {
        checkNotClosed();
        require(data);

        return read(data, 0, data.length);
    }

    @SneakyThrows
    public int read(byte[] buffer, int offset, int count) {
        checkNotClosed();
        require(buffer);
        require(offset, offset >= 0);//ignore count 4 BytesSegment

        return getReader().read(buffer, offset, count);
    }

    @SneakyThrows
    public void write(int b) {
        checkNotClosed();

        getWriter().write(b);
    }

    public void write(byte[] data) {
        checkNotClosed();
        require(data);

        write(data, 0, data.length);
    }

    @SneakyThrows
    public void write(byte[] buffer, int offset, int count) {
        checkNotClosed();
        require(buffer);
        require(offset, offset >= 0);

        getWriter().write(buffer, offset, count);
    }

    public void write(IOStream<?, ?> in, long count) {
        write(in.getReader(), count);
    }

    public void write(InputStream in, long count) {
        copyTo(in, count, getWriter());
    }

    @SneakyThrows
    @Override
    public void flush() {
        checkNotClosed();

        getWriter().flush();
    }

    public void copyTo(IOStream<?, ?> out) {
        require(out);

        copyTo(out.getWriter());
    }

    //重置position
    public void copyTo(OutputStream out) {
        checkNotClosed();

        if (!canSeek()) {
            log.warn("{} can't seek, reset position ignore", this.getClass().getName());
            copyTo(getReader(), out);
            return;
        }
        long pos = getPosition();
        setPosition(0L);
        copyTo(getReader(), out);
        setPosition(pos);
    }
}
