package org.rx.util;

import org.rx.$;
import org.rx.ErrorCode;
import org.rx.SystemException;
import org.rx.cache.BytesSegment;

import java.io.*;

import static org.rx.Contract.require;
import static org.rx.SystemException.values;

public class MemoryStream extends IOStream {
    private static final class BytesWriter extends ByteArrayOutputStream {
        private volatile int minPosition, length, maxLength = Integer.MAX_VALUE - 8;

        public int getPosition() {
            return count;
        }

        public synchronized void setPosition(int position) {
            require(position, minPosition <= position);

            count = position;
        }

        public int getLength() {
            return length;
        }

        public synchronized void setLength(int length) {
            require(length, length <= maxLength);

            this.length = length;
        }

        public synchronized byte[] getBuffer() {
            return buf;
        }

        public synchronized void setBuffer(byte[] buffer) {
            require(buffer);

            buf = buffer;
        }

        public BytesWriter(int capacity) {
            super(capacity);
        }

        public BytesWriter(byte[] buffer, int offset, int count, boolean nonResizable) {
            super(0);
            require(buffer);
            require(offset, offset >= 0);
            if (nonResizable) {
                require(count, offset + count < buffer.length);
                minPosition = offset;
                maxLength = count;
            }

            setBuffer(buffer);
            setPosition(offset);
        }

        @Override
        public synchronized void write(int b) {
            beforeWrite(1);
            super.write(b);
            afterWrite();
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            require(b);

            beforeWrite(len);
            super.write(b, off, len);
            afterWrite();
        }

        private void beforeWrite(int count) {
            require(count, getPosition() + count < maxLength);
        }

        private void afterWrite() {
            if (getPosition() > getLength()) {
                setLength(getPosition());
            }
        }

        @Override
        public synchronized void writeTo(OutputStream out) throws IOException {
            require(out);

            out.write(getBuffer(), minPosition, getLength());
        }

        @Override
        public int size() {
            return getLength();
        }

        @Override
        public void reset() {
            setPosition(minPosition);
        }
    }

    private static final class BytesReader extends ByteArrayInputStream {
        public int getPosition() {
            return pos;
        }

        public synchronized void setPosition(int position) {
            this.pos = position;
        }

        public int getLength() {
            return count;
        }

        public synchronized void setLength(int length) {
            count = length;
        }

        public BytesReader(byte[] buffer, int offset, int count) {
            super(buffer);
            setBuffer(buffer, offset, count, offset);
        }

        public synchronized void setBuffer(byte[] buffer, int offset, int count, int mark) {
            require(buffer);
            require(offset, offset >= 0);
            //require(count, offset + count < buffer.length);

            this.buf = buffer;
            this.pos = offset;
            this.count = count;
            this.mark = mark;
        }
    }

    private boolean     publiclyVisible;
    private BytesWriter writer;
    private BytesReader reader;

    @Override
    public InputStream getReader() {
        checkRead();
        return super.getReader();
    }

    public int getPosition() {
        return writer.getPosition();
    }

    public void setPosition(int position) {
        checkNotClosed();

        writer.setPosition(position);
    }

    public int getLength() {
        return writer.getLength();
    }

    public void setLength(int length) {
        checkNotClosed();

        writer.setLength(length);
    }

    @ErrorCode
    public byte[] getBuffer() {
        checkNotClosed();
        if (!publiclyVisible) {
            throw new SystemException(values());
        }

        return writer.getBuffer();
    }

    public MemoryStream() {
        this(32, false);
    }

    public MemoryStream(int capacity, boolean publiclyVisible) {
        super.writer = writer = new BytesWriter(capacity);
        initReader(publiclyVisible);
    }

    public MemoryStream(byte[] buffer, int offset, int count, boolean nonResizable, boolean publiclyVisible) {
        super.writer = writer = new BytesWriter(buffer, offset, count, nonResizable);
        initReader(publiclyVisible);
    }

    private void initReader(boolean publiclyVisible) {
        super.reader = reader = new BytesReader(writer.getBuffer(), writer.getPosition(), writer.getLength());
        this.publiclyVisible = publiclyVisible;
    }

    private void checkRead() {
        reader.setBuffer(writer.getBuffer(), writer.getPosition(), writer.getLength(), writer.minPosition);
    }

    @Override
    public int available() {
        checkRead();
        return super.available();
    }

    @Override
    public int read() {
        checkRead();
        return super.read();
    }

    @Override
    public int read(byte[] buffer, int offset, int count) {
        checkRead();
        return super.read(buffer, offset, count);
    }

    @Override
    public void copyTo(IOStream to) {
        require(to);

        copyTo(to.getWriter());
    }

    public void copyTo(OutputStream to) {
        checkNotClosed();
        require(to);

        checkRead();
        int mark = reader.getPosition();
        copyTo(reader, to);
        reader.setPosition(mark);
    }

    public void writeTo(IOStream from) {
        checkNotClosed();
        require(from);

        writeTo(from.getWriter());
    }

    public void writeTo(OutputStream from) {
        checkNotClosed();
        require(from);

        try {
            writer.writeTo(from);
        } catch (IOException e) {
            throw new SystemException(e);
        }
    }

    public boolean tryGetBuffer($<BytesSegment> out) {
        checkNotClosed();

        if (out == null || !publiclyVisible) {
            return false;
        }
        out.$ = new BytesSegment(writer.getBuffer(), getPosition(), getLength());
        return true;
    }

    public byte[] toArray() {
        checkNotClosed();

        return writer.toByteArray();
    }
}
