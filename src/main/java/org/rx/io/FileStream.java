package org.rx.io;

import lombok.SneakyThrows;
import org.rx.bean.SUID;

import java.io.*;
import java.nio.channels.FileChannel;

import static org.rx.core.Contract.*;

public class FileStream extends IOStream<FileInputStream, FileOutputStream> implements Serializable {
    private static final long serialVersionUID = 8857792573177348449L;

    @SneakyThrows
    public static File createTempFile() {
        File temp = File.createTempFile(FileStream.class.getSimpleName(), SUID.randomSUID().toString());
        temp.setReadable(true);
        temp.setWritable(true);
        return temp;
    }

    private File file;
    private boolean noDelay;
    private transient RandomAccessFile randomAccessFile;

    private void writeObject(ObjectOutputStream out) throws IOException {
        out.defaultWriteObject();
        long pos = getPosition();
        out.writeLong(pos);
        byte[] buffer = new byte[CONFIG.getBufferSize()];
        int read;
        while ((read = read(buffer, 0, buffer.length)) > 0) {
            out.write(buffer, 0, read);
        }
        out.flush();
        setPosition(pos);
    }

    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        randomAccessFile = new RandomAccessFile(file, noDelay ? "rwd" : "rw");
        long pos = in.readLong();
        setPosition(pos);
        byte[] buffer = new byte[CONFIG.getBufferSize()];
        int read;
        while ((read = in.read(buffer, 0, buffer.length)) > 0) {
            write(buffer, 0, read);
        }
        flush();
    }

    @SneakyThrows
    @Override
    public FileInputStream getReader() {
        if (reader == null) {
            setReader(new FileInputStream(randomAccessFile.getFD()));
        }
        return super.getReader();
    }

    @SneakyThrows
    @Override
    public FileOutputStream getWriter() {
        if (writer == null) {
            super.setWriter(new FileOutputStream(randomAccessFile.getFD()));
        }
        return super.getWriter();
    }

    @Override
    public boolean canSeek() {
        return true;
    }

    @SneakyThrows
    @Override
    public long getPosition() {
        return randomAccessFile.getFilePointer();
    }

    @SneakyThrows
    @Override
    public void setPosition(long position) {
        randomAccessFile.seek(position);
    }

    @SneakyThrows
    @Override
    public long getLength() {
        return randomAccessFile.length();
    }

    @SneakyThrows
    public void setLength(long length) {
        randomAccessFile.setLength(length);
    }

    public FileStream() {
        this(createTempFile());
    }

    public FileStream(String filePath) {
        this(new File(filePath));
    }

    public FileStream(File file) {
        this(file, false);
    }

    @SneakyThrows
    public FileStream(File file, boolean noDelay) {
        super(null, null);
        require(file);
        this.randomAccessFile = new RandomAccessFile(this.file = file, (this.noDelay = noDelay) ? "rwd" : "rw");
    }

    @SneakyThrows
    @Override
    protected void freeObjects() {
        quietly(super::freeObjects);
        randomAccessFile.close();
    }

    @SneakyThrows
    @Override
    public long available() {
        return randomAccessFile.length() - randomAccessFile.getFilePointer();
    }

    @SneakyThrows
    @Override
    public int read() {
        return randomAccessFile.read();
    }

    @SneakyThrows
    @Override
    public int read(byte[] buffer, int offset, int count) {
        return randomAccessFile.read(buffer, offset, count);
    }

    @SneakyThrows
    @Override
    public void write(int b) {
        randomAccessFile.write(b);
    }

    @SneakyThrows
    @Override
    public void write(byte[] buffer, int offset, int count) {
        randomAccessFile.write(buffer, offset, count);
    }

    @SneakyThrows
    @Override
    public void flush() {
        if (noDelay) {
            return;
        }
        randomAccessFile.getChannel().force(false);
    }

    @SneakyThrows
    public void lockRead(long position, long length) {
        randomAccessFile.getChannel().lock(position, length, true);
    }

    @SneakyThrows
    public void lockWrite(long position, long length) {
        randomAccessFile.getChannel().lock(position, length, false);
    }

    @SneakyThrows
    public void unlock(long position, long length) {
        randomAccessFile.getChannel().map(FileChannel.MapMode.READ_WRITE, position, length);
    }
}
