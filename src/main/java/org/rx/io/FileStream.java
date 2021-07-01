package org.rx.io;

import io.netty.buffer.ByteBuf;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FilenameUtils;
import org.rx.bean.DataRange;
import org.rx.bean.FlagsEnum;
import org.rx.bean.SUID;
import org.rx.bean.Tuple;
import org.rx.core.Lazy;
import org.rx.util.function.TripleFunc;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.rx.core.App.*;

@Slf4j
public class FileStream extends IOStream<InputStream, OutputStream> implements Serializable {
    private static final long serialVersionUID = 8857792573177348449L;

    @RequiredArgsConstructor
    @EqualsAndHashCode
    static class Block {
        final long position, size;
    }

    @EqualsAndHashCode(callSuper = true)
    static class MapBlock extends Block {
        final FileChannel.MapMode mode;

        public MapBlock(FileChannel.MapMode mode, long position, long size) {
            super(position, size);
            this.mode = mode;
        }
    }

    @SneakyThrows
    public static File createTempFile() {
        File temp = File.createTempFile(SUID.randomSUID().toString(), ".rfs");
        temp.setReadable(true);
        temp.setWritable(true);
//        temp.deleteOnExit();
        return temp;
    }

    private BufferedRandomAccessFile.FileMode fileMode;
    private FlagsEnum<CompositeLock.Flags> lockFlags;
    @Getter(AccessLevel.PROTECTED)
    private transient BufferedRandomAccessFile randomAccessFile;
    transient final Map<MapBlock, CompositeMmap> mmaps = new ConcurrentHashMap<>(0);
    transient final Lazy<CompositeLock> lock = new Lazy<>(() -> new CompositeLock(this, lockFlags));

    public CompositeLock getLock() {
        return lock.getValue();
    }

    private void writeObject(ObjectOutputStream out) throws IOException {
        out.defaultWriteObject();
        out.writeUTF(randomAccessFile.getPath());
        out.writeLong(getPosition());
        setPosition(0);
        read(out);
    }

    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        if (fileMode == BufferedRandomAccessFile.FileMode.READ_ONLY) {
            fileMode = BufferedRandomAccessFile.FileMode.READ_WRITE;
        }
        File file = new File(in.readUTF());
        if (file.exists()) {
            file = createTempFile();
        }
        try {
            randomAccessFile = new BufferedRandomAccessFile(file, fileMode, BufferedRandomAccessFile.BufSize.SMALL_DATA);
        } catch (Exception e) {
            log.warn("readObject", e);
            randomAccessFile = new BufferedRandomAccessFile(createTempFile(), fileMode, BufferedRandomAccessFile.BufSize.SMALL_DATA);
        }
        long pos = in.readLong();
        write(in);
        setPosition(pos);
    }

    public String getPath() {
        return randomAccessFile.getPath();
    }

    @Override
    public String getName() {
        return FilenameUtils.getName(getPath());
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
                public int read(byte[] b, int off, int len) {
                    return FileStream.this.read(b, off, len);
                }

                @Override
                public int read() {
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
                public void write(byte[] b, int off, int len) {
                    FileStream.this.write(b, off, len);
                }

                @Override
                public void write(int b) {
                    FileStream.this.write(b);
                }

                @Override
                public void flush() {
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

    public FileStream(File file, BufferedRandomAccessFile.FileMode mode, BufferedRandomAccessFile.BufSize size) {
        this(file, mode, size, CompositeLock.Flags.READ_WRITE_LOCK.flags());
    }

    @SneakyThrows
    public FileStream(@NonNull File file, BufferedRandomAccessFile.FileMode mode, BufferedRandomAccessFile.BufSize size, @NonNull FlagsEnum<CompositeLock.Flags> lockFlags) {
        super(null, null);
        this.randomAccessFile = new BufferedRandomAccessFile(file, this.fileMode = mode, size);
        this.lockFlags = lockFlags;
    }

    @SneakyThrows
    @Override
    protected void freeObjects() {
        quietly(() -> {
            super.freeObjects();
            for (CompositeMmap compositeMmap : mmaps.values()) {
                compositeMmap.close();
            }
        });
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
    public int read(byte[] buffer, int offset, int length) {
        return randomAccessFile.read(buffer, offset, length);
    }

    @SneakyThrows
    @Override
    public synchronized int read(ByteBuf dst, int length) {
        long pos = getPosition();
        FileChannel ch = randomAccessFile.getChannel();
        ch.position(pos);

        int totalRead = 0;
        ByteBuffer buffer = ByteBuffer.allocateDirect(Math.min(length, BufferedRandomAccessFile.BufSize.SMALL_DATA.value));
        TripleFunc<ByteBuffer, Integer, ByteBuffer> resetFunc = (b, c) -> {
            b.clear();
            if (c < b.limit()) {
                b.limit(c);
            }
            return b;
        };
        int r;
        while ((r = ch.read(resetFunc.invoke(buffer, length))) > 0) {
            buffer.flip();
            dst.writeBytes(buffer);
            length -= r;
            totalRead += r;
        }
        setPosition(pos + totalRead);
        return totalRead;
    }

    @SneakyThrows
    @Override
    public void write(int b) {
        randomAccessFile.write(b);
    }

    @SneakyThrows
    @Override
    public void write(byte[] buffer, int offset, int length) {
        randomAccessFile.write(buffer, offset, length);
    }

    @Override
    public void write(ByteBuf src, int length) {
        write0(src, length);
    }

    public long write0(ByteBuf src) {
        return write0(src, src.readableBytes());
    }

    @SneakyThrows
    public synchronized long write0(ByteBuf src, int length) {
        long pos = getPosition();
        FileChannel ch = randomAccessFile.getChannel();
        ch.position(pos);

        int rIndex = src.readerIndex(), rEndIndex = rIndex + length;
        ByteBuf buf = src;
        if (buf.readableBytes() != length) {
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

        src.readerIndex(rEndIndex);
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

    @Override
    public void flush() {
        flush(false);
    }

    @SneakyThrows
    public void flush(boolean flushToDisk) {
        randomAccessFile.flush();
        if (flushToDisk) {
            randomAccessFile.sync();
        }
    }

    public CompositeMmap mmap(FileChannel.MapMode mode) {
        long pos = getPosition();
        return mmap(mode, pos, getLength() - pos);
    }

    @SneakyThrows
    public CompositeMmap mmap(FileChannel.MapMode mode, long position, long size) {
        if (mode == null) {
            mode = FileChannel.MapMode.READ_WRITE;
        }
        return mmaps.computeIfAbsent(new MapBlock(mode, position, size), k -> new CompositeMmap(this, k));
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
}
