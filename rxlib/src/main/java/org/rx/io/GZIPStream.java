package org.rx.io;

import io.netty.buffer.ByteBuf;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.rx.core.Constants;

import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

@RequiredArgsConstructor
public class GZIPStream extends DuplexStream {
    private static final long serialVersionUID = 5949731591101041212L;
    private final DuplexStream baseStream;
    private final boolean leaveOpen;
    private transient GZIPInputStream reader;
    private transient GZIPOutputStream writer;

    @Override
    public String getName() {
        return baseStream.getName();
    }

    @SneakyThrows
    private GZIPInputStream reader() {
        if (reader == null) {
            reader = new GZIPInputStream(baseStream, Constants.HEAP_BUF_SIZE);
        }
        return reader;
    }

    @SneakyThrows
    private GZIPOutputStream writer() {
        if (writer == null) {
            writer = new GZIPOutputStream(baseStream.asOutputStream(), Constants.HEAP_BUF_SIZE);
        }
        return writer;
    }

    @Override
    public boolean canSeek() {
        return baseStream.canSeek();
    }

    @Override
    public long getPosition() {
        return baseStream.getPosition();
    }

    @Override
    public void setPosition(long position) {
        baseStream.setPosition(position);
    }

    @Override
    public long getLength() {
        return baseStream.getLength();
    }

    public GZIPStream(DuplexStream stream) {
        this(stream, false);
    }

    @Override
    protected void dispose() throws Throwable {
        if (reader != null) {
            reader.close();
        }
        if (writer != null) {
            writer.close();
        }
        if (!leaveOpen) {
            baseStream.close();
        }
    }

    @SneakyThrows
    @Override
    public int read() {
        checkNotClosed();
        return reader().read();
    }

    @SneakyThrows
    @Override
    public int read(byte[] b, int off, int len) {
        checkNotClosed();
        return reader().read(b, off, len);
    }

    @SneakyThrows
    @Override
    public void write(int b) {
        checkNotClosed();
        writer().write(b);
    }

    @SneakyThrows
    @Override
    public void write(byte[] b, int off, int len) {
        checkNotClosed();
        writer().write(b, off, len);
    }

    @SneakyThrows
    public void finish() {
        if (writer != null) {
            writer.finish();
        }
    }

    @SneakyThrows
    @Override
    public void flush() {
        checkNotClosed();
        if (writer != null) {
            writer.flush();
        }
    }

    @Override
    public byte[] toArray() {
        long pos = getPosition();
        setPosition(0);
        ByteBuf buf = Bytes.heapBuffer();
        try {
            while (read(buf, Constants.HEAP_BUF_SIZE) != Constants.IO_EOF) {
            }
            setPosition(pos);
            return Bytes.toBytes(buf);
        } finally {
            buf.release();
        }
    }
}
