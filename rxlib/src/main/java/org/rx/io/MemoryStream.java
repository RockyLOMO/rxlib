package org.rx.io;

import io.netty.buffer.ByteBuf;
import lombok.NonNull;
import lombok.Setter;
import org.rx.annotation.ErrorCode;
import org.rx.core.Constants;
import org.rx.exception.InvalidException;
import org.rx.util.Snowflake;

import java.io.*;

public final class MemoryStream extends DuplexStream implements Serializable {
    private static final long serialVersionUID = 6209361024929311435L;
    @Setter
    private String name;
    private boolean directBuffer;
    private transient ByteBuf buffer;
    private transient int mark;

    private void writeObject(ObjectOutputStream out) throws IOException {
        out.defaultWriteObject();
        out.writeInt((int) getPosition());
        out.writeInt((int) getLength());
        setPosition(0);
        read(out);
    }

    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        int pos = in.readInt();
        int len = in.readInt();
        buffer = directBuffer ? Bytes.directBuffer(len) : Bytes.heapBuffer(len);
        setLength(len);
        write(in);
        setPosition(pos);
    }

    @Override
    public String getName() {
        if (name == null) {
            name = String.valueOf(Snowflake.DEFAULT.nextId());
        }
        return name;
    }

    @Override
    public boolean canSeek() {
        return true;
    }

    @Override
    public long getPosition() {
        return buffer.readerIndex();
    }

    @Override
    public void setPosition(long position) {
        if (position > Integer.MAX_VALUE) {
            throw new InvalidException("Position > Integer.MAX_VALUE");
        }

        buffer.readerIndex((int) position);
    }

    @Override
    public long getLength() {
        return buffer.writerIndex();
    }

    @Override
    public boolean markSupported() {
        return true;
    }

    @Override
    public synchronized void mark(int readlimit) {
        mark = buffer.readerIndex();
    }

    @Override
    public synchronized void reset() {
        buffer.readerIndex(mark);
    }

    @Override
    public int read(byte[] b, int off, int len) {
        checkNotClosed();
        checkArrayRange(b, off, len);
        if (len == 0) {
            return 0;
        }

        int readableBytes = buffer.readableBytes();
        if (readableBytes == 0) {
            return Constants.IO_EOF;
        }
        int len0 = Math.min(readableBytes, len);
        buffer.readBytes(b, off, len0);
        return len0;
    }

    @Override
    public int read() {
        checkNotClosed();
        if (buffer.readableBytes() == 0) {
            return Constants.IO_EOF;
        }
        return buffer.readByte() & 0xff;
    }

    @Override
    public void write(byte[] b, int off, int len) {
        checkNotClosed();
        checkArrayRange(b, off, len);
        if (len == 0) {
            return;
        }

        buffer.writerIndex(buffer.readerIndex());
        buffer.writeBytes(b, off, len);
        buffer.readerIndex(buffer.writerIndex());
    }

    @Override
    public void write(int b) {
        checkNotClosed();
        buffer.writerIndex(buffer.readerIndex());
        buffer.writeByte(b);
        buffer.readerIndex(buffer.writerIndex());
    }

    public void setLength(int length) {
        buffer.writerIndex(length);
    }

    @ErrorCode
    public ByteBuf getBuffer() {
        checkNotClosed();

        return buffer;
    }

    public MemoryStream() {
        this(Constants.HEAP_BUF_SIZE, false);
    }

    public MemoryStream(boolean directBuffer) {
        this(Constants.HEAP_BUF_SIZE, directBuffer);
    }

    public MemoryStream(int initialCapacity, boolean directBuffer) {
        buffer = (this.directBuffer = directBuffer) ? Bytes.directBuffer(initialCapacity) : Bytes.heapBuffer(initialCapacity);
    }

    public MemoryStream(byte[] buffer, int offset, int length) {
        this(Bytes.wrap(buffer, offset, length), false);
    }

    public MemoryStream(@NonNull ByteBuf buf, boolean forWrite) {
        if (forWrite) {
            buf.readerIndex(buf.writerIndex());
        }
        this.buffer = buf;
    }

    @Override
    protected void dispose() {
        Bytes.release(buffer);
    }

    @Override
    public int read(ByteBuf dst, int length) {
        checkNotClosed();
        checkLength(length);
        if (length == 0) {
            return 0;
        }

        int readableBytes = buffer.readableBytes();
        if (readableBytes == 0) {
            return Constants.IO_EOF;
        }

        int len = Math.min(readableBytes, length);
        buffer.readBytes(dst, len);
        return len;
    }

    @Override
    public int read(ByteBuf dst, int dstIndex, int length) {
        checkNotClosed();
        checkByteBufRange(dst, dstIndex, length);
        if (length == 0) {
            return 0;
        }

        int readableBytes = buffer.readableBytes();
        if (readableBytes == 0) {
            return Constants.IO_EOF;
        }
        int len = Math.min(readableBytes, length);
        buffer.readBytes(dst, dstIndex, len);
        return len;
    }

    @Override
    public void write(ByteBuf src, int length) {
        checkNotClosed();
        checkLength(length);
        if (length > src.readableBytes()) {
            throw new IndexOutOfBoundsException();
        }
        if (length == 0) {
            return;
        }

        buffer.writerIndex(buffer.readerIndex());
        buffer.writeBytes(src, length);
        buffer.readerIndex(buffer.writerIndex());
    }

    @Override
    public void write(ByteBuf src, int srcIndex, int length) {
        checkNotClosed();
        checkByteBufReadableRange(src, srcIndex, length);
        if (length == 0) {
            return;
        }

        buffer.writerIndex(buffer.readerIndex());
        buffer.writeBytes(src, srcIndex, length);
        buffer.readerIndex(buffer.writerIndex());
    }
}
