package org.rx.io;

import io.netty.buffer.ByteBuf;
import lombok.Setter;
import lombok.SneakyThrows;
import org.rx.core.Constants;
import org.rx.exception.InvalidException;

import java.io.File;
import java.io.InputStream;
import java.io.Serializable;

import static org.rx.core.Extends.tryClose;

public final class HybridStream extends DuplexStream implements Serializable {
    private static final long serialVersionUID = 2137331266386948293L;

    public static final int NON_MEMORY_SIZE = 0;
    private final int maxMemorySize;
    private final String tempFilePath;
    private MemoryStream memoryStream;
    private FileStream fileStream;
    private boolean deleteFileOnClose;
    private long position;
    private long length;
    private transient long mark;
    @Setter
    private String name;

    @Override
    public synchronized String getName() {
        if (name != null) {
            return name;
        }
        if (memoryStream != null) {
            return memoryStream.getName();
        }
        return ensureFileStream().getName();
    }

    @Override
    public boolean canSeek() {
        return true;
    }

    @Override
    public synchronized long getPosition() {
        return position;
    }

    @Override
    public synchronized void setPosition(long position) {
        checkNotClosed();
        if (position < 0 || position > length) {
            throw new InvalidException("Position out of range");
        }
        this.position = position;
    }

    @Override
    public synchronized long getLength() {
        return length;
    }

    @Override
    public synchronized long availableBytes() {
        if (isClosed()) {
            return 0;
        }
        return length - position;
    }

    @Override
    public boolean markSupported() {
        return true;
    }

    @Override
    public synchronized void mark(int readlimit) {
        mark = position;
    }

    @Override
    public synchronized void reset() {
        setPosition(mark);
    }

    public HybridStream() {
        this(Constants.MAX_HEAP_BUF_SIZE, false);
    }

    public HybridStream(int maxMemorySize, boolean directMemory) {
        this(maxMemorySize, directMemory, null);
    }

    public HybridStream(int maxMemorySize, boolean directMemory, String tempFilePath) {
        this.maxMemorySize = maxMemorySize <= NON_MEMORY_SIZE ? NON_MEMORY_SIZE : Math.min(maxMemorySize, Constants.MAX_HEAP_BUF_SIZE);
        this.tempFilePath = tempFilePath;
        if (this.maxMemorySize == NON_MEMORY_SIZE) {
            fileStream = newFileStream();
            length = fileStream.getLength();
        } else {
            memoryStream = new MemoryStream(this.maxMemorySize, directMemory);
        }
    }

    @Override
    protected synchronized void dispose() {
        String filePath = deleteFileOnClose && fileStream != null ? fileStream.getPath() : null;
        tryClose(memoryStream);
        tryClose(fileStream);
        if (filePath != null) {
            new File(filePath).delete();
        }
    }

    FileStream newFileStream() {
        FileStream stream = tempFilePath == null ? new FileStream() : new FileStream(tempFilePath);
        if (tempFilePath == null) {
            deleteFileOnClose = true;
        }
        return stream;
    }

    synchronized void checkCapacity() {
        if (maxMemorySize == NON_MEMORY_SIZE || length > maxMemorySize || position >= maxMemorySize) {
            ensureFileStream();
        }
    }

    synchronized boolean hasFileStream() {
        return fileStream != null;
    }

    public synchronized boolean isFileBacked() {
        return fileStream != null;
    }

    synchronized long getMemoryLength() {
        return memoryStream == null ? 0 : Math.min(length, maxMemorySize);
    }

    synchronized long getFileLength() {
        return fileStream == null ? 0 : fileStream.getLength();
    }

    synchronized String getFilePath() {
        return fileStream == null ? null : fileStream.getPath();
    }

    @Override
    public synchronized int read() {
        checkNotClosed();
        if (position >= length) {
            return Constants.IO_EOF;
        }
        if (isMemoryPosition(position)) {
            memoryStream.setPosition(position);
            int value = memoryStream.read();
            if (value != Constants.IO_EOF) {
                position++;
            }
            return value;
        }

        fileStream.setPosition(filePosition(position));
        int value = fileStream.read();
        if (value != Constants.IO_EOF) {
            position++;
        }
        return value;
    }

    @Override
    public synchronized int read(byte[] buffer, int offset, int length) {
        checkNotClosed();
        checkArrayRange(buffer, offset, length);
        if (length == 0) {
            return 0;
        }
        if (position >= this.length) {
            return Constants.IO_EOF;
        }

        int total = 0;
        int remaining = (int) Math.min(length, this.length - position);
        while (remaining > 0) {
            int read;
            if (isMemoryPosition(position)) {
                read = readMemory(buffer, offset + total, remaining);
            } else {
                read = readFile(buffer, offset + total, remaining);
            }
            if (read <= 0) {
                break;
            }
            total += read;
            remaining -= read;
            position += read;
        }
        return total == 0 ? Constants.IO_EOF : total;
    }

    @Override
    public synchronized int read(ByteBuf dst, int length) {
        checkNotClosed();
        checkLength(length);
        if (length == 0) {
            return 0;
        }
        if (position >= this.length) {
            return Constants.IO_EOF;
        }

        dst.ensureWritable((int) Math.min(length, this.length - position));
        int writerIndex = dst.writerIndex();
        int total = read(dst, writerIndex, length);
        if (total > 0) {
            dst.writerIndex(writerIndex + total);
        }
        return total;
    }

    @Override
    public synchronized int read(ByteBuf dst, int dstIndex, int length) {
        checkNotClosed();
        checkByteBufRange(dst, dstIndex, length);
        if (length == 0) {
            return 0;
        }
        if (position >= this.length) {
            return Constants.IO_EOF;
        }

        int total = 0;
        int remaining = (int) Math.min(length, this.length - position);
        while (remaining > 0) {
            int read;
            if (isMemoryPosition(position)) {
                read = readMemory(dst, dstIndex + total, remaining);
            } else {
                read = readFile(dst, dstIndex + total, remaining);
            }
            if (read <= 0) {
                break;
            }
            total += read;
            remaining -= read;
            position += read;
        }
        return total == 0 ? Constants.IO_EOF : total;
    }

    @Override
    public synchronized void write(int b) {
        checkNotClosed();
        truncateBeforeWrite();
        if (isMemoryPosition(position)) {
            memoryStream.setPosition(position);
            memoryStream.write(b);
        } else {
            FileStream fs = ensureFileStream();
            fs.setPosition(filePosition(position));
            fs.write(b);
        }
        position++;
        updateLength();
    }

    @Override
    public synchronized void write(byte[] buffer, int offset, int length) {
        checkNotClosed();
        checkArrayRange(buffer, offset, length);
        if (length == 0) {
            return;
        }

        truncateBeforeWrite();
        int total = 0;
        int remaining = length;
        while (remaining > 0) {
            int write;
            if (isMemoryPosition(position)) {
                write = writeMemory(buffer, offset + total, remaining);
            } else {
                write = writeFile(buffer, offset + total, remaining);
            }
            total += write;
            remaining -= write;
            position += write;
            updateLength();
        }
    }

    @SneakyThrows
    @Override
    public synchronized long write(InputStream in, long length) {
        return super.write(in, length);
    }

    @Override
    public synchronized void write(ByteBuf src, int length) {
        checkNotClosed();
        checkLength(length);
        if (length > src.readableBytes()) {
            throw new IndexOutOfBoundsException();
        }
        if (length == 0) {
            return;
        }

        int readerIndex = src.readerIndex();
        write(src, readerIndex, length);
        src.readerIndex(readerIndex + length);
    }

    @Override
    public synchronized void write(ByteBuf src, int srcIndex, int length) {
        checkNotClosed();
        checkByteBufReadableRange(src, srcIndex, length);
        if (length == 0) {
            return;
        }

        truncateBeforeWrite();
        int total = 0;
        int remaining = length;
        while (remaining > 0) {
            int write;
            if (isMemoryPosition(position)) {
                write = writeMemory(src, srcIndex + total, remaining);
            } else {
                write = writeFile(src, srcIndex + total, remaining);
            }
            total += write;
            remaining -= write;
            position += write;
            updateLength();
        }
    }

    @Override
    public synchronized void flush() {
        checkNotClosed();
        if (fileStream != null) {
            fileStream.flush();
        }
    }

    private boolean isMemoryPosition(long p) {
        return memoryStream != null && p < maxMemorySize;
    }

    private long filePosition(long p) {
        return p - maxMemorySize;
    }

    private FileStream ensureFileStream() {
        if (fileStream == null) {
            fileStream = newFileStream();
        }
        return fileStream;
    }

    private int readMemory(byte[] buffer, int offset, int length) {
        int read = (int) Math.min(length, Math.min(this.length, maxMemorySize) - position);
        memoryStream.setPosition(position);
        return memoryStream.read(buffer, offset, read);
    }

    private int readFile(byte[] buffer, int offset, int length) {
        if (fileStream == null) {
            return Constants.IO_EOF;
        }

        int read = (int) Math.min(length, this.length - position);
        fileStream.setPosition(filePosition(position));
        return fileStream.read(buffer, offset, read);
    }

    private int readMemory(ByteBuf dst, int dstIndex, int length) {
        int read = (int) Math.min(length, Math.min(this.length, maxMemorySize) - position);
        memoryStream.setPosition(position);
        return memoryStream.read(dst, dstIndex, read);
    }

    private int readFile(ByteBuf dst, int dstIndex, int length) {
        if (fileStream == null) {
            return 0;
        }

        int read = (int) Math.min(length, this.length - position);
        fileStream.setPosition(filePosition(position));
        return fileStream.read(dst, dstIndex, read);
    }

    private int writeMemory(byte[] buffer, int offset, int length) {
        int write = (int) Math.min(length, maxMemorySize - position);
        memoryStream.setPosition(position);
        memoryStream.write(buffer, offset, write);
        return write;
    }

    private int writeFile(byte[] buffer, int offset, int length) {
        FileStream fs = ensureFileStream();
        fs.setPosition(filePosition(position));
        fs.write(buffer, offset, length);
        return length;
    }

    private int writeMemory(ByteBuf src, int srcIndex, int length) {
        int write = (int) Math.min(length, maxMemorySize - position);
        memoryStream.setPosition(position);
        memoryStream.write(src, srcIndex, write);
        return write;
    }

    private int writeFile(ByteBuf src, int srcIndex, int length) {
        FileStream fs = ensureFileStream();
        fs.setPosition(filePosition(position));
        long write = fs.write0(src, srcIndex, length);
        if (write <= 0) {
            throw new InvalidException("Write file failed");
        }
        return (int) write;
    }

    private void truncateBeforeWrite() {
        if (position >= length) {
            return;
        }

        length = position;
        if (memoryStream != null && length < maxMemorySize) {
            memoryStream.setPosition(length);
            memoryStream.setLength((int) length);
            if (fileStream != null) {
                fileStream.setLength(0);
            }
            return;
        }
        if (fileStream != null) {
            fileStream.setLength(filePosition(length));
        }
    }

    private void updateLength() {
        if (position > length) {
            length = position;
        }
    }

    protected static void checkArrayRange(byte[] buffer, int offset, int length) {
        if ((offset | length) < 0 || length > buffer.length - offset) {
            throw new IndexOutOfBoundsException();
        }
    }

    protected static void checkLength(int length) {
        if (length < 0) {
            throw new IndexOutOfBoundsException();
        }
    }
}
