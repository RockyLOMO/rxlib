package org.rx.io;

import io.netty.buffer.ByteBuf;
import lombok.NonNull;
import lombok.SneakyThrows;
import org.rx.bean.BiTuple;
import org.rx.bean.DataRange;
import org.rx.bean.SUID;
import org.rx.bean.Tuple;
import org.rx.core.exception.InvalidException;
import org.rx.util.function.TripleFunc;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.rx.core.App.*;

public class FileStream extends IOStream<InputStream, OutputStream> implements Serializable {
    private static final long serialVersionUID = 8857792573177348449L;

    @SneakyThrows
    public static File createTempFile() {
        File temp = File.createTempFile(SUID.randomSUID().toString(), ".rfs");
        temp.setReadable(true);
        temp.setWritable(true);
//        temp.deleteOnExit();
        return temp;
    }

    private File file;
    private BufferedRandomAccessFile.FileMode fileMode;
    transient BufferedRandomAccessFile randomAccessFile;
    transient final Map<BiTuple<FileChannel.MapMode, Long, Long>, CompositeMmap> mmaps = new ConcurrentHashMap<>(0);
    transient final Map<Tuple<Long, Long>, FileLock> locks = new ConcurrentHashMap<>(0);

    private void writeObject(ObjectOutputStream out) throws IOException {
        out.defaultWriteObject();
        out.writeLong(getPosition());
        copyTo(out);
    }

    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        if (fileMode == BufferedRandomAccessFile.FileMode.READ_ONLY) {
            fileMode = BufferedRandomAccessFile.FileMode.READ_WRITE;
        }
        randomAccessFile = new BufferedRandomAccessFile(file, fileMode, BufferedRandomAccessFile.BufSize.SMALL_DATA);
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
    public synchronized long getPosition() {
        return randomAccessFile.getFilePointer();
    }

    @SneakyThrows
    @Override
    public synchronized void setPosition(long position) {
        randomAccessFile.seek(position);
    }

    @SneakyThrows
    @Override
    public long getLength() {
        return randomAccessFile.length();
    }

    @SneakyThrows
    public synchronized void setLength(long length) {
        randomAccessFile.setLength(length);
    }

    public FileStream() {
        this(createTempFile());
    }

    public FileStream(String filePath) {
        this(new File(filePath));
    }

    public FileStream(File file) {
        this(file, BufferedRandomAccessFile.FileMode.READ_WRITE, BufferedRandomAccessFile.BufSize.SMALL_DATA);
    }

    @SneakyThrows
    public FileStream(@NonNull File file, BufferedRandomAccessFile.FileMode mode, BufferedRandomAccessFile.BufSize size) {
        super(null, null);
        this.randomAccessFile = new BufferedRandomAccessFile(this.file = file, this.fileMode = mode, size);
    }

    @SneakyThrows
    @Override
    protected synchronized void freeObjects() {
        quietly(() -> {
            super.freeObjects();
            for (CompositeMmap compositeMmap : mmaps.values()) {
                compositeMmap.close();
            }
        });
        randomAccessFile.close();
        for (FileLock value : locks.values()) {
            value.release();
        }
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
    public void sync() {
        randomAccessFile.sync();
    }

    public long read(ByteBuf buf) {
        return read(buf, buf.writableBytes());
    }

    @SneakyThrows
    public synchronized long read(ByteBuf buf, int count) {
        long pos = getPosition();
        FileChannel ch = randomAccessFile.getChannel();
        ch.position(pos);

        long totalRead = 0;
        ByteBuffer buffer = ByteBuffer.allocateDirect(Math.min(count, BufferedRandomAccessFile.BufSize.SMALL_DATA.value));
        TripleFunc<ByteBuffer, Integer, ByteBuffer> resetFunc = (b, c) -> {
            b.clear();
            if (c < b.limit()) {
                b.limit(c);
            }
            return b;
        };
        int r;
        while ((r = ch.read(resetFunc.invoke(buffer, count))) > 0) {
            buffer.flip();
            buf.writeBytes(buffer);
            count -= r;
            totalRead += r;
        }
        setPosition(pos + totalRead);
        return totalRead;
    }

    public long write(ByteBuf byteBuf) {
        return write(byteBuf, byteBuf.readableBytes());
    }

    @SneakyThrows
    public synchronized long write(ByteBuf byteBuf, int count) {
        long pos = getPosition();
        FileChannel ch = randomAccessFile.getChannel();
        ch.position(pos);

        int rIndex = byteBuf.readerIndex(), rEndIndex = rIndex + count;
        ByteBuf buf = byteBuf;
        if (buf.readableBytes() != count) {
            buf = buf.slice(rIndex, rEndIndex);
        }
        long w;
        switch (buf.nioBufferCount()) {
            case 0:
                w = ch.write(ByteBuffer.wrap(Bytes.getBytes(buf)));
                break;
            case 1:
                w = ch.write(buf.nioBuffer());
                break;
            default:
                w = ch.write(buf.nioBuffers());
                break;
        }

        byteBuf.readerIndex(rEndIndex);
        setPosition(pos + w);
        switch (fileMode) {
            case READ_WRITE_AND_SYNC_CONTENT:
                ch.force(false);
                break;
            case READ_WRITE_AND_SYNC_ALL:
                ch.force(true);
                break;
        }
        return w;
    }

    public CompositeMmap mmap(FileChannel.MapMode mode) {
        return mmap(mode, getPosition(), getLength());
    }

    @SneakyThrows
    public CompositeMmap mmap(FileChannel.MapMode mode, long position, long length) {
        if (mode == null) {
            mode = FileChannel.MapMode.READ_WRITE;
        }
        return mmaps.computeIfAbsent(BiTuple.of(mode, position, length), k -> new CompositeMmap(this, k));
    }

    // java.io.IOException: 请求的操作无法在使用用户映射区域打开的文件上执行 (Windows需要先执行unmap())
    // A mapping, once established, is not dependent upon the file channel that was used to create it. Closing the channel, in particular, has no effect upon the validity of the mapping.
    public void unmap(@NonNull CompositeMmap buf) {
        CompositeMmap compositeMmap = mmaps.remove(buf.key);
        if (compositeMmap == null) {
            return;
        }

        for (Tuple<MappedByteBuffer, DataRange<Long>> tuple : compositeMmap.buffers) {
            release(tuple.left);
        }
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
