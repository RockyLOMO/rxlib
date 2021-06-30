package org.rx.io;

import lombok.Getter;
import lombok.SneakyThrows;

import java.io.*;
import java.nio.charset.StandardCharsets;

public class BinaryStream extends IOStream<DataInputStream, DataOutputStream> {
    private static final long serialVersionUID = 7204373912624988890L;
    private boolean leaveOpen;
    @Getter
    private IOStream<?, ?> baseStream;
    private transient BufferedReader reader2;

    @Override
    public String getName() {
        return baseStream.getName();
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

    public BinaryStream(IOStream<?, ?> stream) {
        this(stream, false);
    }

    public BinaryStream(IOStream<?, ?> stream, boolean leaveOpen) {
        super(new DataInputStream(stream.getReader()), new DataOutputStream(stream.getWriter()));

        baseStream = stream;
        this.leaveOpen = leaveOpen;
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
    public short readShort() {
        return getReader().readShort();
    }

    @SneakyThrows
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
        long len = readLong();
        HybridStream serialize = new HybridStream();
        read(serialize, len);
        serialize.setPosition(0L);
        return IOStream.deserialize(serialize);
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
    public void writeShort(short value) {
        getWriter().writeShort(value);
    }

    @SneakyThrows
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
    public void writeString(String value) {
        getWriter().writeUTF(value);
    }

    public void writeLine(String value) {
        write((value + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
    }

    public <T extends Serializable> void writeObject(T value) {
        IOStream<?, ?> serialize = IOStream.serialize(value);
        writeLong(serialize.getLength());
        write(serialize);
    }
}
