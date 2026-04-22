package org.rx.io;

import io.netty.buffer.ByteBuf;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Constants;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;

import static org.rx.core.Extends.tryClose;

@Slf4j
public final class HybridStream extends IOStream implements Serializable {
    private static final long serialVersionUID = 2137331266386948293L;

    public static final int NON_MEMORY_SIZE = 0;
    private final int maxMemorySize;
    private final String tempFilePath;
    private IOStream stream;
    @Setter
    private String name;

    @Override
    public String getName() {
        if (name == null) {
            return stream.getName();
        }
        return name;
    }

    @Override
    public synchronized InputStream getReader() {
        return stream.getReader();
    }

    @Override
    public synchronized OutputStream getWriter() {
        checkCapacity(0);
        return stream.getWriter();
    }

    @Override
    public synchronized boolean canSeek() {
        return stream.canSeek();
    }

    @Override
    public synchronized long getPosition() {
        return stream.getPosition();
    }

    @Override
    public synchronized void setPosition(long position) {
        checkCapacity();
        stream.setPosition(position);
    }

    @Override
    public synchronized long getLength() {
        return stream.getLength();
    }

    public HybridStream() {
        this(Constants.MAX_HEAP_BUF_SIZE, false);
    }

    public HybridStream(int maxMemorySize, boolean directMemory) {
        this(maxMemorySize, directMemory, null);
    }

    public HybridStream(int maxMemorySize, boolean directMemory, String tempFilePath) {
        this.maxMemorySize = Math.min(maxMemorySize, Constants.MAX_HEAP_BUF_SIZE);
        this.tempFilePath = tempFilePath;
        //todo when memStream write len > MAX_HEAP_BUF_SIZE
        stream = maxMemorySize <= NON_MEMORY_SIZE ? newFileStream() : new MemoryStream(maxMemorySize, directMemory);
    }

    @Override
    protected void dispose() {
        tryClose(stream);
    }

    FileStream newFileStream() {
        if (tempFilePath != null) {
            return new FileStream(tempFilePath);
        }
        final File tempFile = FileStream.createTempFile();
        return new FileStream(tempFile) {
            @Override
            protected void dispose() throws Throwable {
                try {
                    super.dispose();
                } finally {
                    if (tempFile.exists() && !tempFile.delete()) {
                        log.warn("Delete temp file {} fail", tempFile);
                    }
                }
            }
        };
    }

    synchronized void checkCapacity() {
        checkCapacity(0);
    }

    synchronized void checkCapacity(long appendBytes) {
        if (stream instanceof FileStream) {
            return;
        }
        if (maxMemorySize <= NON_MEMORY_SIZE || stream.getLength() + appendBytes > maxMemorySize) {
            switchToFileStream();
        }
    }

    private void switchToFileStream() {
        log.info("Arrival MaxMemorySize[{}] threshold, switch FileStream", maxMemorySize);
        FileStream fs = newFileStream();
        fs.write(stream.rewind());
        stream.close();
        stream = fs;
    }

    @Override
    public synchronized void write(int b) {
        checkCapacity(1);
        stream.write(b);
    }

    @Override
    public synchronized void write(byte[] buffer, int offset, int length) {
        checkNotClosed();
        checkCapacity(length);
        stream.write(buffer, offset, length);
    }

    @Override
    public synchronized long write(InputStream in, long length) {
        checkNotClosed();

        byte[] buffer = Bytes.arrayBuffer();
        boolean readFully = length != NON_READ_FULLY;
        long copyLen = 0;
        int read;
        try {
            while ((!readFully || copyLen < length)
                    && (read = in.read(buffer, 0, readFully ? (int) Math.min(buffer.length, length - copyLen) : buffer.length)) != Constants.IO_EOF) {
                checkCapacity(read);
                stream.write(buffer, 0, read);
                copyLen += read;
            }
            stream.flush();
            return copyLen;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public synchronized void write(ByteBuf src, int length) {
        checkNotClosed();
        checkCapacity(length);
        stream.write(src, length);
    }
}
