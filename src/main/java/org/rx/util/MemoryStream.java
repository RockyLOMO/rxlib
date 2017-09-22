package org.rx.util;

import org.rx.$;
import org.rx.ErrorCode;
import org.rx.SystemException;
import org.rx.cache.BytesSegment;

import java.io.*;

import static org.rx.Contract.require;
import static org.rx.SystemException.values;

public final class MemoryStream extends IOStream {
    private static final class BytesWriter extends ByteArrayOutputStream {
        public int getCount() {
            return count;
        }

        public void setCount(int count) {
            this.count = count;
        }

        public byte[] getBuffer() {
            return buf;
        }

        public BytesWriter(int capacity) {
            super(capacity);
        }
    }

    private static final class BytesReader extends ByteArrayInputStream {
        public int getPosition() {
            return pos;
        }

        public void setPosition(int position) {
            this.pos = position;
        }

        public int getCount() {
            return count;
        }

        public void setCount(int count) {
            this.count = count;
        }

        public byte[] getBuffer() {
            return buf;
        }

        public BytesReader(byte[] buffer) {
            super(buffer);
        }

        public void setBuffer(byte[] buffer, int count) {
            int offset = Math.min(pos, count);
            this.buf = buffer;
            this.pos = offset;
            this.count = Math.min(offset + count, buf.length);
            this.mark = offset;
        }
    }

    private boolean     publiclyVisible;
    private BytesWriter writer;
    private BytesReader reader;

    public int getPosition() {
        checkRefresh();
        return reader.getPosition();
    }

    public void setPosition(int position) {
        checkRefresh();
        reader.setPosition(position);
    }

    public int getLength() {
        return writer.getCount();
    }

    public void setLength(int length) {
        writer.setCount(length);
    }

    @ErrorCode
    public byte[] getBuffer() {
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
        super.reader = reader = new BytesReader(writer.getBuffer());
        this.publiclyVisible = publiclyVisible;
    }

    private void checkRefresh() {
        if (writer.getCount() != reader.getCount() || writer.getBuffer() != reader.getBuffer()) {
            reader.setBuffer(writer.getBuffer(), writer.getCount());
        }
    }

    @Override
    public int available() {
        checkRefresh();
        return super.available();
    }

    @Override
    public int read() {
        checkRefresh();
        return super.read();
    }

    @Override
    public int read(byte[] buffer, int offset, int count) {
        checkRefresh();
        return super.read(buffer, offset, count);
    }

    @Override
    public void copyTo(IOStream to) {
        checkRefresh();
        super.copyTo(to);
    }

    public void copyTo(OutputStream to) {
        checkNotClosed();

        checkRefresh();
        copyTo(reader, to);
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
