package org.rx.util;

import org.rx.Disposable;
import org.rx.SystemException;
import org.rx.cache.BufferSegment;

import java.io.*;

import static org.rx.Contract.require;

public class IOStream extends Disposable implements Closeable, Flushable {
    public static void copyTo(InputStream from, OutputStream to) {
        require(from, to);

        byte[] buffer = new byte[BufferSegment.DefaultBufferSize * 2];
        try {
            int read;
            while ((read = from.read(buffer, 0, buffer.length)) > 0) {
                to.write(buffer, 0, read);
                to.flush();
            }
        } catch (IOException e) {
            throw new SystemException(e);
        }
    }

    private InputStream  reader;
    private OutputStream writer;

    public InputStream getReader() {
        return reader;
    }

    public OutputStream getWriter() {
        return writer;
    }

    public IOStream(InputStream input, OutputStream output) {
        require(input, output);

        this.reader = input;
        this.writer = output;
    }

    @Override
    protected void freeUnmanaged() {
        try {
            writer.close();
            reader.close();
        } catch (IOException ex) {
            throw new SystemException(ex);
        }
    }

    public int available() {
        try {
            return reader.available();
        } catch (IOException ex) {
            throw new SystemException(ex);
        }
    }

    public int read() {
        try {
            return reader.read();
        } catch (IOException ex) {
            throw new SystemException(ex);
        }
    }

    public int read(byte[] data) {
        require(data);

        return read(data, 0, data.length);
    }

    public int read(byte[] buffer, int offset, int count) {
        require(buffer);
        require(offset, offset >= 0);//ignore count 4 BytesSegment

        try {
            return reader.read(buffer, offset, count);
        } catch (IOException ex) {
            throw new SystemException(ex);
        }
    }

    public void write(int b) {
        try {
            writer.write(b);
        } catch (IOException e) {
            throw new SystemException(e);
        }
    }

    public void write(byte[] data) {
        require(data);

        write(data, 0, data.length);
    }

    public void write(byte[] buffer, int offset, int count) {
        require(buffer);
        require(offset, offset >= 0);

        try {
            writer.write(buffer, offset, count);
        } catch (IOException e) {
            throw new SystemException(e);
        }
    }

    @Override
    public void flush() {
        try {
            writer.flush();
        } catch (IOException e) {
            throw new SystemException(e);
        }
    }

    public void copyTo(IOStream to) {
        require(to);

        copyTo(this.reader, to.writer);
    }
}
