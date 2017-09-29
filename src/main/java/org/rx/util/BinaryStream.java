package org.rx.util;

import org.rx.App;
import org.rx.SystemException;
import org.rx.socket.Bytes;

import java.io.*;

import static org.rx.Contract.require;

public class BinaryStream extends IOStream {
    private boolean          leaveOpen;
    private IOStream         baseStream;
    private DataInputStream  reader;
    private BufferedReader   reader2;
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
    protected void freeUnmanaged() {
        if (!leaveOpen) {
            baseStream.close();
        }
    }

    public boolean readBoolean() {
        try {
            return reader.readBoolean();
        } catch (IOException ex) {
            throw SystemException.wrap(ex);
        }
    }

    public byte readByte() {
        try {
            return reader.readByte();
        } catch (IOException ex) {
            throw SystemException.wrap(ex);
        }
    }

    public short readShort() {
        try {
            return reader.readShort();
        } catch (IOException ex) {
            throw SystemException.wrap(ex);
        }
    }

    public int readInt() {
        try {
            return reader.readInt();
        } catch (IOException ex) {
            throw SystemException.wrap(ex);
        }
    }

    public long readLong() {
        try {
            return reader.readLong();
        } catch (IOException ex) {
            throw SystemException.wrap(ex);
        }
    }

    public float readFloat() {
        try {
            return reader.readFloat();
        } catch (IOException ex) {
            throw SystemException.wrap(ex);
        }
    }

    public double readDouble() {
        try {
            return reader.readDouble();
        } catch (IOException ex) {
            throw SystemException.wrap(ex);
        }
    }

    public char readChar() {
        try {
            return reader.readChar();
        } catch (IOException ex) {
            throw SystemException.wrap(ex);
        }
    }

    public String readString() {
        try {
            return reader.readUTF();
        } catch (IOException ex) {
            throw SystemException.wrap(ex);
        }
    }

    public String readLine() {
        if (reader2 == null) {
            reader2 = new BufferedReader(new InputStreamReader(reader));
        }
        try {
            return reader2.readLine();
        } catch (IOException ex) {
            throw SystemException.wrap(ex);
        }
    }

    public <T> T readObject() {
        int len = readInt();
        byte[] data = new byte[len];
        read(data);
        return (T) App.deserialize(data);
    }

    public void writeBoolean(boolean value) {
        try {
            writer.writeBoolean(value);
        } catch (IOException ex) {
            throw SystemException.wrap(ex);
        }
    }

    public void writeByte(byte value) {
        try {
            writer.writeByte(value);
        } catch (IOException ex) {
            throw SystemException.wrap(ex);
        }
    }

    public void writeShort(short value) {
        try {
            writer.writeShort(value);
        } catch (IOException ex) {
            throw SystemException.wrap(ex);
        }
    }

    public void writeInt(int value) {
        try {
            writer.writeInt(value);
        } catch (IOException ex) {
            throw SystemException.wrap(ex);
        }
    }

    public void writeLong(long value) {
        try {
            writer.writeLong(value);
        } catch (IOException ex) {
            throw SystemException.wrap(ex);
        }
    }

    public void writeFloat(float value) {
        try {
            writer.writeFloat(value);
        } catch (IOException ex) {
            throw SystemException.wrap(ex);
        }
    }

    public void writeDouble(double value) {
        try {
            writer.writeDouble(value);
        } catch (IOException ex) {
            throw SystemException.wrap(ex);
        }
    }

    public void writeChar(char value) {
        try {
            writer.writeChar(value);
        } catch (IOException ex) {
            throw SystemException.wrap(ex);
        }
    }

    public void writeString(String value) {
        try {
            writer.writeUTF(value);
        } catch (IOException ex) {
            throw SystemException.wrap(ex);
        }
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
