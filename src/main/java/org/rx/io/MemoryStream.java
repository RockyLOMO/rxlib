package org.rx.io;

import io.netty.buffer.ByteBuf;
import lombok.Setter;
import org.rx.annotation.ErrorCode;
import org.rx.bean.SUID;
import org.rx.core.exception.ApplicationException;
import org.rx.core.exception.InvalidException;

import java.io.*;

import static org.rx.core.App.values;

public final class MemoryStream extends IOStream<InputStream, OutputStream> implements Serializable {
    private static final long serialVersionUID = 6209361024929311435L;
    @Setter
    private String name;
    private boolean directBuffer;
    private boolean publiclyVisible;
    private transient ByteBuf buffer;

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
        buffer = directBuffer ? Bytes.directBuffer(len, true) : Bytes.heapBuffer(len, true);
        setLength(len);
        write(in);
        setPosition(pos);
    }

    @Override
    public String getName() {
        if (name == null) {
            name = SUID.randomSUID().toString();
        }
        return name;
    }

    @Override
    public InputStream getReader() {
        if (reader == null) {
            reader = new InputStream() {
                @Override
                public int available() {
                    return buffer.readableBytes();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    int readableBytes = buffer.readableBytes();
                    if (readableBytes == 0) {
                        return -1;
                    }
                    int len0 = Math.min(readableBytes, len);
                    buffer.readBytes(b, off, len0);
                    return len0;
                }

                //byte -1?
                @Override
                public int read() {
                    if (buffer.readableBytes() == 0) {
                        return -1;
                    }
                    return buffer.readByte();
                }
            };
        }
        return reader;
    }

    @Override
    public OutputStream getWriter() {
        if (writer == null) {
            writer = new OutputStream() {
                @Override
                public void write(byte[] b, int off, int len) {
                    buffer.writerIndex(buffer.readerIndex());
                    buffer.writeBytes(b, off, len);
                    buffer.readerIndex(buffer.writerIndex());
                }

                @Override
                public void write(int b) {
                    buffer.writerIndex(buffer.readerIndex());
                    buffer.writeByte(b);
                    buffer.readerIndex(buffer.writerIndex());
                }
            };
        }
        return writer;
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
            throw new InvalidException("position > Integer.MAX_VALUE");
        }

        buffer.readerIndex((int) position);
    }

    @Override
    public long getLength() {
        return buffer.writerIndex();
    }

    public void setLength(int length) {
        buffer.writerIndex(length);
    }

    @ErrorCode
    public ByteBuf getBuffer() {
        checkNotClosed();
        if (!publiclyVisible) {
            throw new ApplicationException(values());
        }

        return buffer;
    }

    public MemoryStream() {
        this(256, false, false);
    }

    public MemoryStream(int initialCapacity, boolean directBuffer, boolean publiclyVisible) {
        super(null, null);
        buffer = (this.directBuffer = directBuffer) ? Bytes.directBuffer(initialCapacity, true) : Bytes.heapBuffer(initialCapacity, true);
        this.publiclyVisible = publiclyVisible;
    }

    public MemoryStream(byte[] buffer, int offset, int length) {
        super(null, null);
        this.buffer = Bytes.wrap(buffer, offset, length);
    }

    @Override
    protected void freeObjects() {
        buffer.release();
    }

    @Override
    public int read(ByteBuf dst, int length) {
        buffer.readBytes(dst, length);
        return length;
    }

    public void read(ByteBuf dst, int dstIndex, int length) {
        buffer.readBytes(dst, dstIndex, length);
    }

    @Override
    public void write(ByteBuf src, int length) {
        buffer.writeBytes(src, length);
    }

    public void write(ByteBuf src, int srcIndex, int length) {
        buffer.writeBytes(src, srcIndex, length);
    }
}
