package org.rx.io;

import io.netty.buffer.ByteBuf;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.rx.bean.RxConfig;

import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

@RequiredArgsConstructor
public class GZIPStream extends IOStream<GZIPInputStream, GZIPOutputStream> {
    private static final long serialVersionUID = 5949731591101041212L;
    private final IOStream<?, ?> baseStream;
    private final boolean leaveOpen;

    @Override
    public String getName() {
        return baseStream.getName();
    }

    @SneakyThrows
    @Override
    protected GZIPInputStream initReader() {
        return new GZIPInputStream(baseStream.getReader(), RxConfig.HEAP_BUF_SIZE);
    }

    @SneakyThrows
    @Override
    protected GZIPOutputStream initWriter() {
        return new GZIPOutputStream(baseStream.getWriter(), RxConfig.HEAP_BUF_SIZE);
    }

    @Override
    public boolean canSeek() {
        return baseStream.canSeek();
    }

    @Override
    public boolean canWrite() {
        return baseStream.canWrite();
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

    public GZIPStream(IOStream<?, ?> stream) {
        this(stream, false);
    }

    @Override
    protected void freeObjects() {
        finish();
        if (!leaveOpen) {
            baseStream.close();
        }
    }

    @SneakyThrows
    public void finish() {
        getWriter().finish();
    }

    @Override
    public synchronized byte[] toArray() {
        long pos = getPosition();
        setPosition(0);
        ByteBuf buf = Bytes.heapBuffer();
        while (read(buf, RxConfig.HEAP_BUF_SIZE) != -1) {
        }
        setPosition(pos);
        return Bytes.getBytes(buf);
    }
}
