package org.rx.io;

import lombok.SneakyThrows;
import org.rx.App;
import org.rx.Disposable;
import org.rx.ErrorCode;
import org.rx.SystemException;
import org.rx.bean.Const;
import org.rx.util.StringBuilder;

import java.io.*;

import static org.rx.Contract.require;
import static org.rx.Contract.values;

public class IOStream extends Disposable implements Closeable, Flushable {
    public static String readString(InputStream stream) {
        return readString(stream, Const.Utf8);
    }

    @SneakyThrows
    public static String readString(InputStream stream, String charset) {
        require(stream, charset);

        StringBuilder result = new StringBuilder();
        try (DataInputStream reader = new DataInputStream(stream)) {
            byte[] buffer = new byte[Const.DefaultBufferSize];
            int read;
            while ((read = reader.read(buffer)) > 0) {
                result.append(new String(buffer, 0, read, charset));
            }
        }
        return result.toString();
    }

    public static void writeString(OutputStream stream, String value) {
        writeString(stream, value, Const.Utf8);
    }

    @SneakyThrows
    public static void writeString(OutputStream stream, String value, String charset) {
        require(stream, charset);

        try (DataOutputStream writer = new DataOutputStream(stream)) {
            byte[] data = value.getBytes(charset);
            writer.write(data);
        }
    }

    public static void copyTo(InputStream from, OutputStream to) {
        require(from, to);

        byte[] buffer = new byte[Const.DefaultBufferSize * 2];
        try {
            int read;
            while ((read = from.read(buffer, 0, buffer.length)) > 0) {
                to.write(buffer, 0, read);
                to.flush();
            }
        } catch (IOException ex) {
            throw SystemException.wrap(ex);
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

    @Override
    protected void freeUnmanaged() {
        App.catchCall(this::flush);
        try {
            writer.close();
            reader.close();
        } catch (IOException ex) {
            throw SystemException.wrap(ex);
        }
    }

    public int available() {
        checkNotClosed();

        try {
            return reader.available();
        } catch (IOException ex) {
            throw SystemException.wrap(ex);
        }
    }

    public int read() {
        checkNotClosed();

        try {
            return reader.read();
        } catch (IOException ex) {
            throw SystemException.wrap(ex);
        }
    }

    public int read(byte[] data) {
        checkNotClosed();
        require(data);

        return read(data, 0, data.length);
    }

    public int read(byte[] buffer, int offset, int count) {
        checkNotClosed();
        require(buffer);
        require(offset, offset >= 0);//ignore count 4 BytesSegment

        try {
            return reader.read(buffer, offset, count);
        } catch (IOException ex) {
            throw SystemException.wrap(ex);
        }
    }

    public void write(int b) {
        checkNotClosed();

        try {
            writer.write(b);
        } catch (IOException ex) {
            throw SystemException.wrap(ex);
        }
    }

    public void write(byte[] data) {
        checkNotClosed();
        require(data);

        write(data, 0, data.length);
    }

    public void write(byte[] buffer, int offset, int count) {
        checkNotClosed();
        require(buffer);
        require(offset, offset >= 0);

        try {
            writer.write(buffer, offset, count);
        } catch (IOException ex) {
            throw SystemException.wrap(ex);
        }
    }

    @Override
    public void flush() {
        checkNotClosed();

        try {
            writer.flush();
        } catch (IOException ex) {
            throw SystemException.wrap(ex);
        }
    }

    public void copyTo(IOStream to) {
        checkNotClosed();
        require(to);

        copyTo(this.reader, to.writer);
    }
}
