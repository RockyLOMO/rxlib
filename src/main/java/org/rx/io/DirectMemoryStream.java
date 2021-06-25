package org.rx.io;

import io.netty.buffer.ByteBuf;
import lombok.Getter;
import lombok.Setter;
import org.rx.bean.SUID;
import org.rx.core.exception.InvalidException;

import java.io.*;

public final class DirectMemoryStream extends IOStream<InputStream, OutputStream> implements Serializable {
    @Setter
    private String name;
    @Getter
    private transient ByteBuf buffer;

    private void writeObject(ObjectOutputStream out) throws IOException {
        out.defaultWriteObject();
        out.writeInt((int) getPosition());
        int len = (int) getLength();
        out.writeInt((int) getLength());
        setPosition(0);
        copyTo(getReader(),  out);
    }

    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        int pos = in.readInt();
        int len = in.readInt();
        buffer = Bytes.directBuffer(len, true);
        setLength(len);
        copyTo(in, getWriter());
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

    public DirectMemoryStream() {
        this(256);
    }

    public DirectMemoryStream(int c) {
        super(null, null);
        buffer = Bytes.directBuffer(c, true);
    }

    @Override
    protected void freeObjects() {
        buffer.release();
    }

//    @Override
//    public void write(int b) {
//        super.write(b);
//    }
//
//    @Override
//    public void write(@NonNull byte[] data) {
//        super.write(data);
//    }
//
//    @Override
//    public void write(@NonNull byte[] buffer, int offset, int count) {
//        super.write(buffer, offset, count);
//    }
//
//    @Override
//    public void write(@NonNull IOStream<?, ?> in, long count) {
//        super.write(in, count);
//    }
//
//    @Override
//    public void write(InputStream in, long count) {
//        super.write(in, count);
//    }
}
