package org.rx.io;

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
        return reader().read();
    }

    @SneakyThrows
    @Override
    public int read(byte[] b, int off, int len) {
        checkNotClosed();
        return reader().read(b, off, len);
    }

    @SneakyThrows
    @Override
    public void write(int b) {
        checkNotClosed();
        writer().write(b);
    }

    @SneakyThrows
    @Override
    public void write(byte[] b, int off, int len) {
        checkNotClosed();
        writer().write(b, off, len);
    }

    @SneakyThrows
    @Override
    public void flush() {
        checkNotClosed();
        if (writer != null) {
            writer.flush();
        }
    }

    @SneakyThrows
    public boolean readBoolean() {
        return reader().readBoolean();
    }

    @SneakyThrows
    public byte readByte() {
        return reader().readByte();
    }

    @SneakyThrows
    @Override
    public short readShort() {
        return reader().readShort();
    }

    @SneakyThrows
    @Override
    public int readInt() {
        return reader().readInt();
    }

    @SneakyThrows
    public long readLong() {
        return reader().readLong();
    }

    @SneakyThrows
    public float readFloat() {
        return reader().readFloat();
    }

    @SneakyThrows
    public double readDouble() {
        return reader().readDouble();
    }

    @SneakyThrows
    public char readChar() {
        return reader().readChar();
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
        writer().writeBoolean(value);
    }

    @SneakyThrows
    public void writeByte(byte value) {
        writer().writeByte(value);
    }

    @SneakyThrows
    @Override
    public void writeShort(short value) {
        writer().writeShort(value);
    }

    @SneakyThrows
    @Override
    public void writeInt(int value) {
        writer().writeInt(value);
    }

    @SneakyThrows
    public void writeLong(long value) {
        writer().writeLong(value);
    }

    @SneakyThrows
    public void writeFloat(float value) {
        writer().writeFloat(value);
    }

    @SneakyThrows
    public void writeDouble(double value) {
        writer().writeDouble(value);
    }

    @SneakyThrows
    public void writeChar(char value) {
        writer().writeChar(value);
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
