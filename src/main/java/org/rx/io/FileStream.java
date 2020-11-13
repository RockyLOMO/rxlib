package org.rx.io;

import lombok.SneakyThrows;
import org.rx.bean.SUID;
import org.rx.bean.Tuple;
import org.rx.core.exception.InvalidException;

import java.io.*;
import java.nio.channels.FileLock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.rx.core.Contract.*;

public class FileStream extends IOStream<InputStream, OutputStream> implements Serializable {
    private static final long serialVersionUID = 8857792573177348449L;
    public static final int BUFFER_SIZE_4K = 1024 * 4;

    @SneakyThrows
    public static File createTempFile() {
        File temp = File.createTempFile(SUID.randomSUID().toString(), ".rfs");
        temp.setReadable(true);
        temp.setWritable(true);
//        temp.deleteOnExit();
        return temp;
    }

    @SneakyThrows
    public static BufferedRandomAccessFile createRandomAccessFile(File file, boolean largeFile) {
        return new BufferedRandomAccessFile(file, "rwd", largeFile ? BufferedRandomAccessFile.BuffSz_ : BUFFER_SIZE_4K);
    }

    private File file;
    //todo java.nio.MappedByteBuffer
    private transient BufferedRandomAccessFile randomAccessFile;
    private transient Map<Tuple<Long, Long>, FileLock> locks = new ConcurrentHashMap<>();

    private void writeObject(ObjectOutputStream out) throws IOException {
        out.defaultWriteObject();
        out.writeLong(getPosition());
        copyTo(out);
    }

    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        randomAccessFile = createRandomAccessFile(file, false);
        long pos = in.readLong();
        copyTo(in, this.getWriter());
        setPosition(pos);
    }

    public String getPath() {
        return file.getPath();
    }

    @Override
    public String getName() {
        return file.getName();
    }

    @SneakyThrows
    @Override
    public InputStream getReader() {
        if (reader == null) {
//            setReader(new BufferedInputStream(new FileInputStream(randomAccessFile.getFD()), BUFFER_SIZE_4K));
            setReader(new InputStream() {
                @Override
                public int available() throws IOException {
                    return (int) randomAccessFile.bytesRemaining();
                }

                @Override
                public int read() throws IOException {
                    return FileStream.this.read();
                }
            });
        }
        return super.getReader();
    }

    @SneakyThrows
    @Override
    public OutputStream getWriter() {
        if (writer == null) {
            //RandomAccessFile 搭配
//            super.setWriter(new BufferedOutputStream(new FileOutputStream(randomAccessFile.getFD()), BUFFER_SIZE_4K));
            setWriter(new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    FileStream.this.write(b);
                }

                @Override
                public void flush() throws IOException {
                    FileStream.this.flush();
                }
            });
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
    public FileStream(File file, boolean largeFile) {
        super(null, null);
        require(file);
        this.randomAccessFile = createRandomAccessFile(this.file = file, largeFile);
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
        randomAccessFile.flush();
    }

    @SneakyThrows
    public void lockRead(long position, long length) {
        locks.computeIfAbsent(Tuple.of(position, length), k -> sneakyInvoke(() -> randomAccessFile.getChannel().lock(position, length, true)));
    }

    @SneakyThrows
    public void lockWrite(long position, long length) {
        locks.computeIfAbsent(Tuple.of(position, length), k -> sneakyInvoke(() -> randomAccessFile.getChannel().lock(position, length, false)));
    }

    @SneakyThrows
    public void unlock(long position, long length) {
        FileLock lock = locks.remove(Tuple.of(position, length));
        if (lock == null) {
            throw new InvalidException("File position={} length={} not locked", position, length);
        }
        lock.release();
    }
}
