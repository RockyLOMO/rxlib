package org.rx.io;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.SneakyThrows;
import org.rx.core.Disposable;
import org.rx.annotation.ErrorCode;
import org.rx.core.Contract;
import org.rx.core.StringBuilder;
import org.rx.core.exception.ApplicationException;

import java.io.*;

import static org.rx.core.Contract.*;

@AllArgsConstructor
@Getter
public class IOStream<TI extends InputStream, TO extends OutputStream> extends Disposable implements Closeable, Flushable, Serializable {
    private static final long serialVersionUID = 3204673656139586437L;

    public static String readString(InputStream stream) {
        return readString(stream, Contract.UTF_8);
    }

    @SneakyThrows
    public static String readString(InputStream stream, String charset) {
        require(stream, charset);

        StringBuilder result = new StringBuilder();
        try (DataInputStream reader = new DataInputStream(stream)) {
            byte[] buffer = new byte[CONFIG.getBufferSize()];
            int read;
            while ((read = reader.read(buffer)) > 0) {
                result.append(new String(buffer, 0, read, charset));
            }
        }
        return result.toString();
    }

    public static void writeString(OutputStream stream, String value) {
        writeString(stream, value, Contract.UTF_8);
    }

    @SneakyThrows
    public static void writeString(OutputStream stream, String value, String charset) {
        require(stream, charset);

        try (DataOutputStream writer = new DataOutputStream(stream)) {
            byte[] data = value.getBytes(charset);
            writer.write(data);
        }
    }

    @SneakyThrows
    public static void copyTo(InputStream from, OutputStream to) {
        require(from, to);

        byte[] buffer = new byte[CONFIG.getBufferSize() * 2];
        int read;
        while ((read = from.read(buffer, 0, buffer.length)) > 0) {
            to.write(buffer, 0, read);
        }
        to.flush();
    }

    protected transient TI reader;
    protected transient TO writer;

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
    public int getPosition() {
        throw new ApplicationException(values());
    }

    @ErrorCode
    public void setPosition(int position) {
        throw new ApplicationException(values());
    }

    @ErrorCode
    public int getLength() {
        throw new ApplicationException(values(this.getClass().getSimpleName()));
    }

    @SneakyThrows
    @Override
    protected void freeObjects() {
        quietly(this::flush);

        writer.close();
        reader.close();
    }

    @SneakyThrows
    public int available() {
        checkNotClosed();

        return reader.available();
    }

    @SneakyThrows
    public int read() {
        checkNotClosed();

        return reader.read();
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

        return reader.read(buffer, offset, count);
    }

    @SneakyThrows
    public void write(int b) {
        checkNotClosed();

        writer.write(b);
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

        writer.write(buffer, offset, count);
    }

    @SneakyThrows
    @Override
    public void flush() {
        checkNotClosed();

        writer.flush();
    }

    public void copyTo(IOStream to) {
        require(to);
        copyTo(to.writer);
    }

    public void copyTo(OutputStream to) {
        checkNotClosed();

        copyTo(this.reader, to);
    }
}
