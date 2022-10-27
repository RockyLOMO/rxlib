package org.rx.io;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;

import java.io.*;
import java.nio.charset.StandardCharsets;

@RequiredArgsConstructor
public class BinaryStream extends IOStream<DataInputStream, DataOutputStream> {
    private static final long serialVersionUID = 7204373912624988890L;
    @Getter
    private final IOStream<?, ?> baseStream;
    private final boolean leaveOpen;
    private transient BufferedReader reader2;

    @Override
    public String getName() {
        return baseStream.getName();
    }

    @Override
    protected DataInputStream initReader() {
        return new DataInputStream(baseStream.getReader());
    }

    @Override
    protected DataOutputStream initWriter() {
        return new DataOutputStream(baseStream.getWriter());
    }

    @Override
    public boolean canSeek() {
        return baseStream.canSeek();
    }

    @Override
    public boolean canWrite() {
        return baseStream.canWrite();
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

    public BinaryStream(IOStream<?, ?> stream) {
        this(stream, false);
    }

    @Override
    protected void freeObjects() {
        if (!leaveOpen) {
            baseStream.close();
        }
    }

    @SneakyThrows
    public boolean readBoolean() {
        return getReader().readBoolean();
    }

    @SneakyThrows
    public byte readByte() {
        return getReader().readByte();
    }

    @SneakyThrows
    @Override
    public short readShort() {
        return getReader().readShort();
    }

    @SneakyThrows
    @Override
    public int readInt() {
        return getReader().readInt();
    }

    @SneakyThrows
    public long readLong() {
        return getReader().readLong();
    }

    @SneakyThrows
    public float readFloat() {
        return getReader().readFloat();
    }

    @SneakyThrows
    public double readDouble() {
        return getReader().readDouble();
    }

    @SneakyThrows
    public char readChar() {
        return getReader().readChar();
    }

    @SneakyThrows
    public String readString() {
        return getReader().readUTF();
    }

    @SneakyThrows
    public String readLine() {
        if (reader2 == null) {
            reader2 = new BufferedReader(new InputStreamReader(getReader()));
        }
        return reader2.readLine();
    }

    public <T extends Serializable> T readObject() {
        try (HybridStream serialize = new HybridStream()) {
            read(serialize, readLong());
            return Serializer.DEFAULT.deserialize(serialize.rewind());
        }
    }

    @SneakyThrows
    public void writeBoolean(boolean value) {
        getWriter().writeBoolean(value);
    }

    @SneakyThrows
    public void writeByte(byte value) {
        getWriter().writeByte(value);
    }

    @SneakyThrows
    @Override
    public void writeShort(short value) {
        getWriter().writeShort(value);
    }

    @SneakyThrows
    @Override
    public void writeInt(int value) {
        getWriter().writeInt(value);
    }

    @SneakyThrows
    public void writeLong(long value) {
        getWriter().writeLong(value);
    }

    @SneakyThrows
    public void writeFloat(float value) {
        getWriter().writeFloat(value);
    }

    @SneakyThrows
    public void writeDouble(double value) {
        getWriter().writeDouble(value);
    }

    @SneakyThrows
    public void writeChar(char value) {
        getWriter().writeChar(value);
    }

    @SneakyThrows
    @Override
    public void writeString(String value) {
        getWriter().writeUTF(value);
    }

    public void writeLine(String value) {
        write((value + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
    }

    public <T extends Serializable> void writeObject(T value) {
        try (IOStream<?, ?> stream = Serializer.DEFAULT.serialize(value)) {
            writeLong(stream.getLength());
            write(stream);
        }
    }
}
