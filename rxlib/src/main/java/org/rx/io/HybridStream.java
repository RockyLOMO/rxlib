package org.rx.io;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Constants;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;

@Slf4j
public final class HybridStream extends IOStream implements Serializable {
    private static final long serialVersionUID = 2137331266386948293L;
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
        checkCapacity();
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
        if ((this.maxMemorySize = maxMemorySize) > Constants.MAX_HEAP_BUF_SIZE) {
            log.warn("maxMemorySize gt {}", Constants.MAX_HEAP_BUF_SIZE);
        }
        this.tempFilePath = tempFilePath;
        stream = maxMemorySize <= 0 ? newFileStream() : new MemoryStream(maxMemorySize, directMemory);
    }

    @Override
    protected void freeObjects() {
        stream.close();
    }

    FileStream newFileStream() {
        return tempFilePath == null ? new FileStream() : new FileStream(tempFilePath);
    }

    synchronized void checkCapacity() {
        if (stream instanceof FileStream) {
            return;
        }
        if (stream.getLength() > maxMemorySize) {
            log.info("Arrival MaxMemorySize[{}] threshold, switch FileStream", maxMemorySize);
            FileStream fs = newFileStream();
            fs.write(stream.rewind());
            stream.close();
            stream = fs;
        }
    }
}
