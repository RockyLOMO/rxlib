package org.rx.io;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import lombok.NonNull;
import lombok.SneakyThrows;
import org.rx.bean.SUID;
import org.rx.bean.Tuple;
import org.rx.core.exception.InvalidException;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.rx.core.App.*;

public class FileStream extends IOStream<InputStream, OutputStream> implements Serializable {
    private static final long serialVersionUID = 8857792573177348449L;
    static final Map<ByteBuf, ByteBuffer[]> weakRef = Collections.synchronizedMap(new WeakHashMap<>());

    @SneakyThrows
    public static File createTempFile() {
        File temp = File.createTempFile(SUID.randomSUID().toString(), ".rfs");
        temp.setReadable(true);
        temp.setWritable(true);
//        temp.deleteOnExit();
        return temp;
    }

    private File file;
    private transient BufferedRandomAccessFile randomAccessFile;
    private transient Map<Tuple<Long, Long>, FileLock> locks = new ConcurrentHashMap<>();

    private void writeObject(ObjectOutputStream out) throws IOException {
        out.defaultWriteObject();
        out.writeLong(getPosition());
        copyTo(out);
    }

    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        randomAccessFile = new BufferedRandomAccessFile(file, BufferedRandomAccessFile.FileMode.READ_WRITE, BufferedRandomAccessFile.BufSize.SMALL_DATA);
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
        this(file, BufferedRandomAccessFile.FileMode.READ_WRITE, BufferedRandomAccessFile.BufSize.SMALL_DATA);
    }

    @SneakyThrows
    public FileStream(@NonNull File file, BufferedRandomAccessFile.FileMode mode, BufferedRandomAccessFile.BufSize size) {
        super(null, null);
        this.randomAccessFile = new BufferedRandomAccessFile(this.file = file, mode, size);
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
    public int read(ByteBuf buf) {
        ByteBuffer byteBuffer = buf.nioBuffer();
        byteBuffer = ByteBuffer.allocateDirect(1);
//        randomAccessFile.getChannel().position(getPosition());
        int read = randomAccessFile.getChannel().read(byteBuffer);

        return read;
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
    public void write(ByteBuf buf) {
//        byte[] buffer = Bytes.from(buf);
//        write(buffer, 0, buffer.length);
        int write = randomAccessFile.getChannel().write(buf.nioBuffer());
        setPosition(getPosition() + write);
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

    public ByteBuf mmap(FileChannel.MapMode mode) {
        return mmap(mode, getPosition(), getLength());
    }

    @SneakyThrows
    public synchronized ByteBuf mmap(FileChannel.MapMode mode, long position, long length) {
        if (mode == null) {
            mode = FileChannel.MapMode.READ_WRITE;
        }
        long len = length - position;
        long max = Integer.MAX_VALUE;
        ByteBuffer[] composite = new ByteBuffer[(int) Math.floorDiv(len, max) + 1];
        for (int i = 0; i < composite.length; i++) {
            composite[i] = randomAccessFile.getChannel().map(mode, 0, Math.min(max, len));
            len -= max;
        }
        ByteBuf buf = Unpooled.wrappedBuffer(composite);

        weakRef.put(buf, composite);
        return proxy(ByteBuf.class, (m, p) -> {
            Object r = p.fastInvoke(buf);
            if (m.getName().equals("release")) {
                unmap(buf);
            }
            return r;
        });
    }

    public ByteBuffer[] mmapRawComposite(@NonNull ByteBuf buf) {
        ByteBuffer[] buffers = weakRef.get(buf);
        Objects.requireNonNull(buffers);
        return buffers;
    }

    // Windows won't let us modify the file length while the file is mmapped
    // java.io.IOException: 请求的操作无法在使用用户映射区域打开的文件上执行 (在Windows上需要执行unmap(mmap))
    // A mapping, once established, is not dependent upon the file channel that was used to create it.  Closing the channel, in particular, has no effect upon the validity of the mapping.
    public void unmap(@NonNull ByteBuf buf) {
        ByteBuffer[] buffers = weakRef.remove(buf);
        if (buffers == null) {
            return;
        }

        for (ByteBuffer buffer : buffers) {
            release(buffer);
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
