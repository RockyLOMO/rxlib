package org.rx.io;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.rx.bean.RxConfig;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;

@Slf4j
public final class HybridStream extends IOStream<InputStream, OutputStream> implements Serializable {
    private static final long serialVersionUID = 2137331266386948293L;
    private final int maxMemorySize;
    private final String tempFilePath;
    private MemoryStream memoryStream = new MemoryStream();
    private FileStream fileStream;
    @Setter
    private String name;

    private synchronized IOStream<?, ?> getStream() {
        if (fileStream != null) {
            return fileStream;
        }
        if (memoryStream.getLength() > maxMemorySize) {
            log.info("Arrival MaxMemorySize[{}] threshold, switch FileStream", maxMemorySize);
            memoryStream.copyTo(fileStream = tempFilePath == null ? new FileStream() : new FileStream(tempFilePath));
            fileStream.setPosition(memoryStream.getPosition());
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
    public boolean canSeek() {
        return getStream().canSeek();
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
        this(RxConfig.MAX_HEAP_BUF_SIZE, null);
    }

    public HybridStream(int maxMemorySize, String tempFilePath) {
        super(null, null);
        this.maxMemorySize = maxMemorySize;
        this.tempFilePath = tempFilePath;
    }

    @Override
    protected void freeObjects() {
        getStream().close();
    }

    @Override
    public long available() {
        return getStream().available();
    }

    @Override
    public int read() {
        return getStream().read();
    }

    @Override
    public int read(byte[] buffer, int offset, int count) {
        return getStream().read(buffer, offset, count);
    }

    @Override
    public void write(int b) {
        getStream().write(b);
    }

    @Override
    public void write(byte[] buffer, int offset, int count) {
        getStream().write(buffer, offset, count);
    }

    @Override
    public void flush() {
        getStream().flush();
    }

    @Override
    public void copyTo(IOStream<?, ?> out) {
        getStream().copyTo(out);
    }

    @Override
    public void copyTo(OutputStream out) {
        getStream().copyTo(out);
    }
}
