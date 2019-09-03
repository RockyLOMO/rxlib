package org.rx.io;

import lombok.SneakyThrows;
import org.rx.core.App;
import org.rx.socks.Bytes;

import java.io.*;

import static org.rx.core.Contract.require;

public class BinaryStream extends IOStream {
    private boolean leaveOpen;
    private IOStream baseStream;
    private DataInputStream reader;
    private BufferedReader reader2;
    private DataOutputStream writer;

    public IOStream getBaseStream() {
        return baseStream;
    }

    @Override
    public boolean canSeek() {
        return baseStream.canSeek();
    }

    @Override
    public int getPosition() {
        return baseStream.getPosition();
    }

    @Override
    public void setPosition(int position) {
        baseStream.setPosition(position);
    }

    @Override
    public int getLength() {
        return baseStream.getLength();
    }

    public BinaryStream(IOStream stream) {
        this(stream, false);
    }

    public BinaryStream(IOStream stream, boolean leaveOpen) {
        require(stream);

        super.reader = reader = new DataInputStream(stream.getReader());
        super.writer = writer = new DataOutputStream(stream.getWriter());
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
        return reader.readBoolean();
    }

    @SneakyThrows
    public byte readByte() {
        return reader.readByte();
    }

    @SneakyThrows
    public short readShort() {
        return reader.readShort();
    }

    @SneakyThrows
    public int readInt() {
        return reader.readInt();
    }

    @SneakyThrows
    public long readLong() {
        return reader.readLong();
    }

    @SneakyThrows
    public float readFloat() {
        return reader.readFloat();
    }

    @SneakyThrows
    public double readDouble() {
        return reader.readDouble();
    }

    @SneakyThrows
    public char readChar() {
        return reader.readChar();
    }

    @SneakyThrows
    public String readString() {
        return reader.readUTF();
    }

    @SneakyThrows
    public String readLine() {
        if (reader2 == null) {
            reader2 = new BufferedReader(new InputStreamReader(reader));
        }
        return reader2.readLine();
    }

    public <T> T readObject() {
        int len = readInt();
        byte[] data = new byte[len];
        read(data);
        return (T) App.deserialize(data);
    }

    @SneakyThrows
    public void writeBoolean(boolean value) {
        writer.writeBoolean(value);
    }

    @SneakyThrows
    public void writeByte(byte value) {
        writer.writeByte(value);
    }

    @SneakyThrows
    public void writeShort(short value) {
        writer.writeShort(value);
    }

    @SneakyThrows
    public void writeInt(int value) {
        writer.writeInt(value);
    }

    @SneakyThrows
    public void writeLong(long value) {
        writer.writeLong(value);
    }

    @SneakyThrows
    public void writeFloat(float value) {
        writer.writeFloat(value);
    }

    @SneakyThrows
    public void writeDouble(double value) {
        writer.writeDouble(value);
    }

    @SneakyThrows
    public void writeChar(char value) {
        writer.writeChar(value);
    }

    @SneakyThrows
    public void writeString(String value) {
        writer.writeUTF(value);
    }

    public void writeLine(String value) {
        write(Bytes.getBytes(value + System.lineSeparator()));
    }

    public <T> void writeObject(T value) {
        byte[] data = App.serialize(value);
        writeInt(data.length);
        write(data);
    }
}
