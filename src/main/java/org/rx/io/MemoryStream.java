package org.rx.io;

import lombok.SneakyThrows;
import org.rx.bean.$;
import org.rx.annotation.ErrorCode;
import org.rx.core.exception.ApplicationException;
import org.rx.net.BytesSegment;

import java.io.*;
import java.util.Arrays;

import static org.rx.core.Contract.*;

public class MemoryStream extends IOStream<MemoryStream.BytesReader, MemoryStream.BytesWriter> implements Serializable {
    private static final long serialVersionUID = 1171318600626020868L;

    public static final class BytesWriter extends ByteArrayOutputStream {
        private volatile int minPosition, length, maxLength = MAX_INT;

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
                require(count, offset + count <= buffer.length);
                minPosition = offset;
                maxLength = count;
            }

            setBuffer(buffer);
            setPosition(offset);
            setLength(count);
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

    public static final class BytesReader extends ByteArrayInputStream {
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

    private boolean publiclyVisible;

    private void writeObject(ObjectOutputStream out) throws IOException {
        out.defaultWriteObject();
        BytesWriter writer = getWriter();
        out.writeInt(writer.getPosition());
        out.writeInt(writer.getLength());
        out.write(writer.getBuffer(), 0, writer.getLength());
    }

    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        int pos = in.readInt();
        int len = in.readInt();
        BytesWriter writer = new BytesWriter(new byte[len], 0, len, false);
        setWriter(writer);
        copyTo(in, len, writer);
        writer.setPosition(pos);
        initReader(publiclyVisible);
    }

    @Override
    public MemoryStream.BytesReader getReader() {
        checkRead();
        return super.getReader();
    }

    @Override
    public boolean canSeek() {
        return true;
    }

    @Override
    public long getPosition() {
        return getWriter().getPosition();
    }

    @Override
    public void setPosition(long position) {
        checkNotClosed();

        getWriter().setPosition((int) position);
        checkRead();
    }

    @Override
    public long getLength() {
        return getWriter().getLength();
    }

    public void setLength(int length) {
        checkNotClosed();

        getWriter().setLength(length);
        checkRead();
    }

    @ErrorCode
    public byte[] getBuffer() {
        checkNotClosed();
        if (!publiclyVisible) {
            throw new ApplicationException(values());
        }

        return getWriter().getBuffer();
    }

    public MemoryStream() {
        this(32, false);
    }

    public MemoryStream(int capacity, boolean publiclyVisible) {
        super(null, new BytesWriter(capacity));
        initReader(publiclyVisible);
    }

    public MemoryStream(byte[] buffer, int offset, int count) {
        this(buffer, offset, count, true, false);
    }

    public MemoryStream(byte[] buffer, int offset, int count, boolean nonResizable, boolean publiclyVisible) {
        super(null, new BytesWriter(buffer, offset, count, nonResizable));
        initReader(publiclyVisible);
    }

    private void initReader(boolean publiclyVisible) {
        BytesWriter writer = getWriter();
        setReader(new BytesReader(writer.getBuffer(), writer.getPosition(), writer.getLength()));
        this.publiclyVisible = publiclyVisible;
    }

    private void checkRead() {
        BytesWriter writer = getWriter();
        super.getReader().setBuffer(writer.getBuffer(), writer.getPosition(), writer.getLength(), writer.minPosition);
    }

    @Override
    public long available() {
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
        int size = super.read(buffer, offset, count);
        setPosition(getPosition() + size);
        return size;
    }

    @Override
    public void copyTo(OutputStream out) {
        checkNotClosed();

        checkRead();
        super.copyTo(out);
    }

    @Override
    public void write(int b) {
        super.write(b);
        checkRead();
    }

    @Override
    public void write(byte[] buffer, int offset, int count) {
        super.write(buffer, offset, count);
        checkRead();
    }

    @Override
    public void write(InputStream in, long count) {
        super.write(in, count);
        checkRead();
    }

    public void writeTo(IOStream out) {
        require(out);

        writeTo(out.getWriter());
    }

    @SneakyThrows
    public void writeTo(OutputStream out) {
        checkNotClosed();

        getWriter().writeTo(out);
    }

    public boolean tryGetBuffer($<BytesSegment> out) {
        checkNotClosed();

        if (out == null || !publiclyVisible) {
            return false;
        }
        BytesWriter writer = getWriter();
        out.v = new BytesSegment(writer.getBuffer(), writer.getPosition(), writer.getLength());
        return true;
    }

    public synchronized byte[] toArray() {
        checkNotClosed();

        BytesWriter writer = getWriter();
        return Arrays.copyOf(writer.getBuffer(), writer.getLength());
    }
}
