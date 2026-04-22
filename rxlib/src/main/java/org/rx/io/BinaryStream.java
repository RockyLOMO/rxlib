package org.rx.io;

import io.netty.buffer.ByteBuf;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;

import java.io.*;
import java.nio.charset.StandardCharsets;

@RequiredArgsConstructor
public class BinaryStream extends DuplexStream {
    private static final long serialVersionUID = 7204373912624988890L;
    @Getter
    private final DuplexStream baseStream;
    private final boolean leaveOpen;
    private transient DataInputStream reader;
    private transient DataOutputStream writer;
    private transient BufferedReader lineReader;

    @Override
    public String getName() {
        return baseStream.getName();
    }

    private DataInputStream reader() {
        if (reader == null) {
            reader = new DataInputStream(baseStream);
        }
        return reader;
    }

    private DataOutputStream writer() {
        if (writer == null) {
            writer = new DataOutputStream(baseStream.asOutputStream());
        }
        return writer;
    }

    @Override
    public boolean canSeek() {
        return baseStream.canSeek();
    }

    @Override
    public long getPosition() {
        return baseStream.getPosition();
    }

    @Override
    public void setPosition(long position) {
        baseStream.setPosition(position);
    }

    @Override
    public long getLength() {
        return baseStream.getLength();
    }

    public BinaryStream(DuplexStream stream) {
        this(stream, false);
    }

    @Override
    protected void dispose() {
        if (!leaveOpen) {
            baseStream.close();
        }
    }

    @SneakyThrows
    @Override
    public int read() {
        checkNotClosed();
        return baseStream.read();
    }

    @SneakyThrows
    @Override
    public int read(byte[] b, int off, int len) {
        checkNotClosed();
        return baseStream.read(b, off, len);
    }

    @Override
    public int read(ByteBuf dst, int length) {
        checkNotClosed();
        return baseStream.read(dst, length);
    }

    @Override
    public int read(ByteBuf dst, int dstIndex, int length) {
        checkNotClosed();
        return baseStream.read(dst, dstIndex, length);
    }

    @SneakyThrows
    @Override
    public void write(int b) {
        checkNotClosed();
        baseStream.write(b);
    }

    @SneakyThrows
    @Override
    public void write(byte[] b, int off, int len) {
        checkNotClosed();
        baseStream.write(b, off, len);
    }

    @Override
    public void write(ByteBuf src, int length) {
        checkNotClosed();
        baseStream.write(src, length);
    }

    @Override
    public void write(ByteBuf src, int srcIndex, int length) {
        checkNotClosed();
        baseStream.write(src, srcIndex, length);
    }

    @SneakyThrows
    @Override
    public void flush() {
        checkNotClosed();
        if (writer != null) {
            writer.flush();
        } else {
            baseStream.flush();
        }
    }

    @SneakyThrows
    public boolean readBoolean() {
        int value = read();
        if (value < 0) {
            throw new EOFException();
        }
        return value != 0;
    }

    @SneakyThrows
    public byte readByte() {
        int value = read();
        if (value < 0) {
            throw new EOFException();
        }
        return (byte) value;
    }

    @SneakyThrows
    @Override
    public short readShort() {
        return super.readShort();
    }

    @SneakyThrows
    @Override
    public int readInt() {
        return super.readInt();
    }

    @SneakyThrows
    public long readLong() {
        return ((long) readInt() << 32) + (readInt() & 0xffffffffL);
    }

    @SneakyThrows
    public float readFloat() {
        return Float.intBitsToFloat(readInt());
    }

    @SneakyThrows
    public double readDouble() {
        return Double.longBitsToDouble(readLong());
    }

    @SneakyThrows
    public char readChar() {
        return (char) readShort();
    }

    @SneakyThrows
    public String readString() {
        return reader().readUTF();
    }

    @SneakyThrows
    public String readLine() {
        if (lineReader == null) {
            lineReader = new BufferedReader(new InputStreamReader(this));
        }
        return lineReader.readLine();
    }

    public <T extends Serializable> T readObject() {
        try (HybridStream serialize = new HybridStream()) {
            read(serialize, readLong());
            return Serializer.DEFAULT.deserialize(serialize.rewind());
        }
    }

    @SneakyThrows
    public void writeBoolean(boolean value) {
        write(value ? 1 : 0);
    }

    @SneakyThrows
    public void writeByte(byte value) {
        write(value);
    }

    @SneakyThrows
    @Override
    public void writeShort(short value) {
        super.writeShort(value);
    }

    @SneakyThrows
    @Override
    public void writeInt(int value) {
        super.writeInt(value);
    }

    @SneakyThrows
    public void writeLong(long value) {
        writeInt((int) (value >>> 32));
        writeInt((int) value);
    }

    @SneakyThrows
    public void writeFloat(float value) {
        writeInt(Float.floatToIntBits(value));
    }

    @SneakyThrows
    public void writeDouble(double value) {
        writeLong(Double.doubleToLongBits(value));
    }

    @SneakyThrows
    public void writeChar(char value) {
        writeShort((short) value);
    }

    @SneakyThrows
    @Override
    public void writeString(String value) {
        writer().writeUTF(value);
    }

    public void writeLine(String value) {
        write((value + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
    }

    public <T extends Serializable> void writeObject(T value) {
        try (DuplexStream stream = Serializer.DEFAULT.serialize(value)) {
            writeLong(stream.getLength());
            write(stream);
        }
    }
}
