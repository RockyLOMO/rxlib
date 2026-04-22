package org.rx.io;

import io.netty.buffer.ByteBuf;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.rx.bean.FlagsEnum;
import org.rx.core.Constants;
import org.rx.util.Lazy;
import org.rx.util.Snowflake;

import javax.activation.MimetypesFileTypeMap;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.nio.file.attribute.UserDefinedFileAttributeView;

import static org.rx.core.Extends.tryClose;

@Slf4j
public class FileStream extends DuplexStream implements Serializable {
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
            randomAccessFile = new BufferedRandomAccessFile(file, fileMode, Constants.MEDIUM_BUF);
        } catch (Exception e) {
            log.warn("readObject", e);
            randomAccessFile = new BufferedRandomAccessFile(createTempFile(), fileMode, Constants.MEDIUM_BUF);
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

    public synchronized boolean canWrite() {
        return !isClosed() && fileMode != FileMode.READ_ONLY;
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
    public synchronized long getLength() {
        return randomAccessFile.length();
    }

    @SneakyThrows
    public synchronized void setLength(long length) {
        randomAccessFile.setLength(length);
    }

    @SneakyThrows
    public synchronized String getAttribute(String attrName) {
        UserDefinedFileAttributeView view = java.nio.file.Files.getFileAttributeView(Paths.get(randomAccessFile.getPath()), UserDefinedFileAttributeView.class);
        ByteBuffer buf = ByteBuffer.allocate(view.size(attrName));
        view.read(attrName, buf);
        buf.flip();
        return StandardCharsets.UTF_8.decode(buf).toString();
    }

    @SneakyThrows
    public synchronized void setAttribute(String attrName, String attrValue) {
        UserDefinedFileAttributeView view = java.nio.file.Files.getFileAttributeView(Paths.get(randomAccessFile.getPath()), UserDefinedFileAttributeView.class);
        if (attrValue == null) {
            view.delete(attrName);
            return;
        }
        view.write(attrName, StandardCharsets.UTF_8.encode(attrValue));
    }

    public FileStream() {
        this(createTempFile());
    }

    public FileStream(String filePath) {
        this(new File(filePath));
    }

    public FileStream(File file) {
        this(file, FileMode.READ_WRITE, Constants.MEDIUM_BUF);
    }

    public FileStream(File file, FileMode mode, int bufSize) {
        this(file, mode, bufSize, CompositeLock.Flags.READ_WRITE_LOCK.flags());
    }

    @SneakyThrows
    public FileStream(@NonNull File file, FileMode mode, int bufSize, @NonNull FlagsEnum<CompositeLock.Flags> lockFlags) {
        this.randomAccessFile = new BufferedRandomAccessFile(file, this.fileMode = mode, bufSize);
        this.lockFlags = lockFlags;
    }

    @Override
    protected void dispose() throws Throwable {
        tryClose(randomAccessFile);
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
    public synchronized long availableBytes() {
        return randomAccessFile.bytesRemaining();
    }

    @SneakyThrows
    @Override
    public synchronized int read(byte[] b, int off, int len) {
        checkNotClosed();
        return randomAccessFile.read(b, off, len);
    }

    @SneakyThrows
    @Override
    public synchronized int read() {
        checkNotClosed();
        return randomAccessFile.read();
    }

    @SneakyThrows
    @Override
    public synchronized void write(byte[] b, int off, int len) {
        checkNotClosed();
        randomAccessFile.write(b, off, len);
    }

    @SneakyThrows
    @Override
    public synchronized void write(int b) {
        checkNotClosed();
        randomAccessFile.write(b);
    }

    @SneakyThrows
    @Override
    public synchronized int read(ByteBuf dst, int length) {
        checkNotClosed();
        checkLength(length);
        if (length == 0) {
            return 0;
        }

        dst.ensureWritable(length);
        int writerIndex = dst.writerIndex();
        int totalRead = read(dst, writerIndex, length);
        if (totalRead > 0) {
            dst.writerIndex(writerIndex + totalRead);
        }
        return totalRead;
    }

    @SneakyThrows
    @Override
    public synchronized int read(ByteBuf dst, int dstIndex, int length) {
        checkNotClosed();
        checkByteBufRange(dst, dstIndex, length);
        if (length == 0) {
            return 0;
        }

        long pos = getPosition();
        FileChannel ch = randomAccessFile.getChannel();
        ch.position(pos);

        int totalRead = 0;
        while (length > 0) {
            int r = dst.setBytes(dstIndex + totalRead, ch, length);
            if (r <= 0) {
                break;
            }
            length -= r;
            totalRead += r;
        }
        setPosition(pos + totalRead);
        return totalRead == 0 ? Constants.IO_EOF : totalRead;
    }

    @Override
    public void write(ByteBuf src, int length) {
        write0(src, length);
    }

    public long write0(ByteBuf src) {
        return write0(src, src.readableBytes());
    }

    @Override
    public void write(ByteBuf src, int srcIndex, int length) {
        write0(src, srcIndex, length);
    }

    @SneakyThrows
    public synchronized long write0(ByteBuf src, int length) {
        checkNotClosed();
        checkLength(length);
        if (length > src.readableBytes()) {
            throw new IndexOutOfBoundsException();
        }
        if (length == 0) {
            return 0;
        }

        int readerIndex = src.readerIndex();
        long written = write0(src, readerIndex, length);
        src.readerIndex(readerIndex + (int) written);
        return written;
    }

    @SneakyThrows
    public synchronized long write0(ByteBuf src, int srcIndex, int length) {
        checkNotClosed();
        checkByteBufReadableRange(src, srcIndex, length);
        if (length == 0) {
            return 0;
        }

        long pos = getPosition();
        FileChannel ch = randomAccessFile.getChannel();
        ch.position(pos);

        int written = 0;
        while (written < length) {
            int w = src.getBytes(srcIndex + written, ch, length - written);
            if (w <= 0) {
                break;
            }
            written += w;
        }

        setPosition(pos + written);
//        switch (fileMode) {
//            case READ_WRITE_AND_SYNC_CONTENT:
//                ch.force(false);
//                break;
//            case READ_WRITE_AND_SYNC_ALL:
//                ch.force(true);
//                break;
//        }
        return written;
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
