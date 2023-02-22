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
    private MemoryStream memoryStream;
    private FileStream fileStream;
    @Setter
    private String name;

    private synchronized IOStream getStream() {
        if (fileStream != null) {
            return fileStream;
        }
        if (memoryStream.getLength() > maxMemorySize) {
            log.info("Arrival MaxMemorySize[{}] threshold, switch FileStream", maxMemorySize);
            fileStream = tempFilePath == null ? new FileStream() : new FileStream(tempFilePath);
            fileStream.write(memoryStream.rewind());
            memoryStream.close();
            memoryStream = null;
            return fileStream;
        }
        return memoryStream;
    }

    @Override
    public String getName() {
        if (name == null) {
            return getStream().getName();
        }
        return name;
    }

    @Override
    public InputStream getReader() {
        return getStream().getReader();
    }

    @Override
    public OutputStream getWriter() {
        return getStream().getWriter();
    }

    @Override
    public boolean canWrite() {
        return getStream().canWrite();
    }

    @Override
    public long getPosition() {
        return getStream().getPosition();
    }

    @Override
    public void setPosition(long position) {
        getStream().setPosition(position);
    }

    @Override
    public long getLength() {
        return getStream().getLength();
    }

    public HybridStream() {
        this(Constants.MAX_HEAP_BUF_SIZE, false, null);
    }

    public HybridStream(int maxMemorySize, boolean directMemory, String tempFilePath) {
        this.maxMemorySize = maxMemorySize;
        this.tempFilePath = tempFilePath;
        memoryStream = new MemoryStream(Constants.HEAP_BUF_SIZE, directMemory);
    }

    @Override
    protected void freeObjects() {
        getStream().close();
    }
}
