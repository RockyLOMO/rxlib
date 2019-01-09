package org.rx.io;

import lombok.SneakyThrows;
import org.rx.common.App;
import org.rx.common.Disposable;
import org.rx.annotation.ErrorCode;
import org.rx.common.SystemException;
import org.rx.common.Contract;
import org.rx.util.StringBuilder;

import java.io.*;

import static org.rx.common.Contract.require;
import static org.rx.common.Contract.values;

public class IOStream extends Disposable implements Closeable, Flushable {
    public static String readString(InputStream stream) {
        return readString(stream, Contract.Utf8);
    }

    @SneakyThrows
    public static String readString(InputStream stream, String charset) {
        require(stream, charset);

        StringBuilder result = new StringBuilder();
        try (DataInputStream reader = new DataInputStream(stream)) {
            byte[] buffer = new byte[Contract.DefaultBufferSize];
            int read;
            while ((read = reader.read(buffer)) > 0) {
                result.append(new String(buffer, 0, read, charset));
            }
        }
        return result.toString();
    }

    public static void writeString(OutputStream stream, String value) {
        writeString(stream, value, Contract.Utf8);
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

        byte[] buffer = new byte[Contract.DefaultBufferSize * 2];
        int read;
        while ((read = from.read(buffer, 0, buffer.length)) > 0) {
            to.write(buffer, 0, read);
            to.flush();
        }
    }

    protected InputStream  reader;
    protected OutputStream writer;

    public InputStream getReader() {
        return reader;
    }

    public OutputStream getWriter() {
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
    public int getPosition() {
        throw new SystemException(values());
    }

    @ErrorCode
    public void setPosition(int position) {
        throw new SystemException(values());
    }

    @ErrorCode(messageKeys = { "$type" })
    public int getLength() {
        throw new SystemException(values(this.getClass().getSimpleName()));
    }

    protected IOStream() {
    }

    public IOStream(InputStream input, OutputStream output) {
        require(input, output);

        this.reader = input;
        this.writer = output;
    }

    @SneakyThrows
    @Override
    protected void freeObjects() {
        App.catchCall(this::flush);

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
        checkNotClosed();
        require(to);

        copyTo(this.reader, to.writer);
    }
}
