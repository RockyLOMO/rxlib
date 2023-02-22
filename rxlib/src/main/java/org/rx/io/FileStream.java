package org.rx.io;

import io.netty.buffer.ByteBuf;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.rx.bean.FlagsEnum;
import org.rx.util.Lazy;
import org.rx.util.Snowflake;
import org.rx.util.function.TripleFunc;

import javax.activation.MimetypesFileTypeMap;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

@Slf4j
public class FileStream extends IOStream implements Serializable {
    private static final long serialVersionUID = 8857792573177348449L;

    @RequiredArgsConstructor
    @EqualsAndHashCode
    public static class Block {
        public final long position, size;
    }

    @SneakyThrows
    public static File createTempFile() {
        File temp = File.createTempFile(String.valueOf(Snowflake.DEFAULT.nextId()), ".rfs");
        temp.setReadable(true);
        temp.setWritable(true);
//        temp.deleteOnExit();
        return temp;
    }

    private FileMode fileMode;
    private FlagsEnum<CompositeLock.Flags> lockFlags;
    @Getter(AccessLevel.PROTECTED)
    private transient BufferedRandomAccessFile randomAccessFile;
    private transient final Lazy<CompositeLock> lock = new Lazy<>(() -> new CompositeLock(this, lockFlags));
    private transient InputStream reader;
    private transient OutputStream writer;

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
        if (fileMode == FileMode.READ_ONLY) {
            fileMode = FileMode.READ_WRITE;
        }
        File file = new File(in.readUTF());
        if (file.exists()) {
            file = createTempFile();
        }
        try {
            randomAccessFile = new BufferedRandomAccessFile(file, fileMode, BufferedRandomAccessFile.MEDIUM_BUF);
        } catch (Exception e) {
            log.warn("readObject", e);
            randomAccessFile = new BufferedRandomAccessFile(createTempFile(), fileMode, BufferedRandomAccessFile.MEDIUM_BUF);
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
        return Files.getName(getPath());
    }

    public String getContentType() {
        MimetypesFileTypeMap mimeTypesMap = new MimetypesFileTypeMap();
        return mimeTypesMap.getContentType(getPath());
    }

    @Override
    public InputStream getReader() {
        if (reader == null) {
            reader = new InputStream() {
                @Override
                public int available() {
                    return safeRemaining(FileStream.this.available());
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    return randomAccessFile.read(b, off, len);
                }

                @Override
                public int read() throws IOException {
                    return randomAccessFile.read();
                }
            };
        }
        return reader;
    }

    @Override
    public OutputStream getWriter() {
        if (writer == null) {
            writer = new OutputStream() {
                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    randomAccessFile.write(b, off, len);
                }

                @Override
                public void write(int b) throws IOException {
                    randomAccessFile.write(b);
                }

                @Override
                public void flush() {
                    FileStream.this.flush();
                }
            };
        }
        return writer;
    }

    @Override
    public synchronized boolean canWrite() {
        return super.canWrite() && fileMode != FileMode.READ_ONLY;
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
    public synchronized long getLength() {
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
        this(file, FileMode.READ_WRITE, BufferedRandomAccessFile.MEDIUM_BUF);
    }

    public FileStream(File file, FileMode mode, int bufSize) {
        this(file, mode, bufSize, CompositeLock.Flags.READ_WRITE_LOCK.flags());
    }

    @SneakyThrows
    public FileStream(@NonNull File file, FileMode mode, int bufSize, @NonNull FlagsEnum<CompositeLock.Flags> lockFlags) {
        this.randomAccessFile = new BufferedRandomAccessFile(file, this.fileMode = mode, bufSize);
        this.lockFlags = lockFlags;
    }

    @SneakyThrows
    @Override
    protected void freeObjects() {
        randomAccessFile.close();
    }

    @Override
    public synchronized byte[] toArray() {
        return super.toArray();
    }

    public synchronized final FileStream flip() {
        setLength(getPosition());
        setPosition(0);
        return this;
    }

    @SneakyThrows
    @Override
    public synchronized long available() {
        return randomAccessFile.bytesRemaining();
    }

    @SneakyThrows
    @Override
    public synchronized int read(ByteBuf dst, int length) {
        long pos = getPosition();
        FileChannel ch = randomAccessFile.getChannel();
        ch.position(pos);

        int totalRead = 0;
        ByteBuffer buffer = ByteBuffer.allocateDirect(Math.min(length, BufferedRandomAccessFile.MEDIUM_BUF));
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
//        switch (fileMode) {
//            case READ_WRITE_AND_SYNC_CONTENT:
//                ch.force(false);
//                break;
//            case READ_WRITE_AND_SYNC_ALL:
//                ch.force(true);
//                break;
//        }
        return w;
    }

    @Override
    public void flush() {
        flush(false);
    }

    @SneakyThrows
    public synchronized void flush(boolean flushToDisk) {
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
        return new CompositeMmap(this, mode, new Block(position, size));
    }
}
