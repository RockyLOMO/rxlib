package org.rx.io;

import io.netty.buffer.ByteBuf;
import io.netty.util.concurrent.FastThreadLocal;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.App;
import org.rx.core.exception.InvalidException;
import org.rx.util.function.BiAction;

import java.io.*;
import java.nio.channels.FileChannel;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public final class WALFileStream extends IOStream<InputStream, OutputStream> {
    private static final long serialVersionUID = 1414441456982833443L;

    private void writeObject(ObjectOutputStream out) throws IOException {
        throw new UnsupportedEncodingException();
    }

    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        throw new UnsupportedEncodingException();
    }

    static class MetaHeader implements Serializable {
        private static final long serialVersionUID = 3894764623767567837L;

        transient BiAction<MetaHeader> writeBack;
        @Getter
        private volatile long logPosition;
        private final AtomicInteger size = new AtomicInteger();

        public void setLogPosition(long logPosition) {
            this.logPosition = logPosition;
            writeBack();
        }

        public int getSize() {
            return size.get();
        }

        public void setSize(int size) {
            this.size.set(size);
            writeBack();
        }

        public int incrementSize() {
            writeBack();
            return size.incrementAndGet();
        }

        public int decrementSize() {
            writeBack();
            return size.decrementAndGet();
        }

        @SneakyThrows
        private void writeBack() {
            if (writeBack == null) {
                return;
            }
            writeBack.invoke(this);
        }
    }

    interface ReadInvoker {
        int invoke(IOStream<?, ?> reader);
    }

    static final float GROW_FACTOR = 0.75f;
    static final int HEADER_SIZE = 256;
    private final FileStream main;
    final CompositeLock lock;
    private final long growLength;
    private final int readerCount;
    private IOStream<?, ?> writer;
    private final LinkedTransferQueue<IOStream<?, ?>> readers = new LinkedTransferQueue<>();
    private final FastThreadLocal<Long> readerPosition = new FastThreadLocal<>();
    private final Serializer serializer;
    final MetaHeader meta;
    private final WriteBackQueue writeBackQueue = new WriteBackQueue();

    @Override
    public String getName() {
        return main.getName();
    }

    @Override
    protected InputStream initReader() {
        return new InputStream() {
            @Override
            public int available() {
                return safeRemaining(WALFileStream.this.available());
            }

            @Override
            public int read(byte[] b, int off, int len) {
                return ensureRead(reader -> reader.read(b, off, len));
            }

            @Override
            public int read() {
                return ensureRead(IOStream::read);
            }
        };
    }

    @Override
    protected OutputStream initWriter() {
        return new OutputStream() {
            @Override
            public void write(byte[] b, int off, int len) {
                ensureWrite(writer -> writer.write(b, off, len));
            }

            @Override
            public void write(int b) {
                ensureWrite(writer -> writer.write(b));
            }

            @Override
            public void flush() {
                WALFileStream.this.flush();
            }
        };
    }

    public long getReaderPosition() {
        Long val = readerPosition.getIfExists();
        if (val == null) {
            throw new InvalidException("Reader position not set");
        }
        return val;
    }

    public void setReaderPosition(long position) {
        readerPosition.set(position);
    }

    public void removeReaderPosition() {
        readerPosition.remove();
    }

    @Override
    public long getLength() {
        return lock.readInvoke(main::getLength);
    }

    public WALFileStream(File file, long growLength, int readerCount, Serializer serializer) {
        this.growLength = growLength;
        this.readerCount = readerCount;
        this.serializer = serializer;

        main = new FileStream(file, FileMode.READ_WRITE, BufferedRandomAccessFile.BufSize.TINY_DATA);
        lock = main.getLock();
        if (!ensureGrow()) {
            createReaderAndWriter();
        }

        meta = loadMeta();
        meta.writeBack = m -> writeBackQueue.offer(0, this::saveMeta);
    }

    @Override
    protected void freeObjects() {
        releaseReaderAndWriter();

        writeBackQueue.consume();
        main.close();
    }

    public void clear() {
        lock.writeInvoke(() -> {
            meta.setLogPosition(HEADER_SIZE);
            meta.setSize(0);
        });
    }

    public void saveMeta() {
        checkNotClosed();

        lock.writeInvoke(() -> {
            meta.setLogPosition(writer.getPosition());
            writer.setPosition(0);
            serializer.serialize(meta, writer);
        }, 0, HEADER_SIZE);
    }

    @SneakyThrows
    private MetaHeader loadMeta() {
        IOStream<?, ?> reader = readers.take();
        try {
            return lock.readInvoke(() -> {
                reader.setPosition(0);
                try {
                    return serializer.deserialize(reader, true);
                } catch (Exception e) {
                    if (e instanceof StreamCorruptedException) {
                        App.log("loadMeta", e);
                        return new MetaHeader();
                    }
                    throw e;
                }
            }, 0, HEADER_SIZE);
        } finally {
            readers.offer(reader);
        }
    }

    private void createReaderAndWriter() {
        lock.writeInvoke(() -> {
            long length = getLength();
            writer = main.mmap(FileChannel.MapMode.READ_WRITE, 0, length);

            for (int i = 0; i < readerCount; i++) {
                readers.add(main.mmap(FileChannel.MapMode.READ_ONLY, 0, length));
            }
            if (readers.isEmpty()) {
                readers.add(writer);
            }
        });
    }

    private void releaseReaderAndWriter() {
        lock.writeInvoke(() -> {
            if (writer != null) {
                writer.close();
            }

            IOStream<?, ?> tmp;
            while ((tmp = readers.poll()) != null) {
                tmp.close();
            }
        });
    }

    private boolean ensureGrow() {
        return lock.writeInvoke(() -> {
            long length = main.getLength();
            if (length < growLength) {
                log.debug("growLength {} 0->{}", getName(), growLength);
                _setLength(growLength);
                return true;
            }

            if (meta != null && meta.getLogPosition() / (float) length > GROW_FACTOR) {
                long resize = length + growLength;
                log.debug("growLength {} {}->{}", getName(), length, resize);
                _setLength(resize);
                return true;
            }

            return false;
        });
    }

    private void _setLength(long length) {
        releaseReaderAndWriter();
        main.setLength(length);
        createReaderAndWriter();
    }

    @SneakyThrows
    @Override
    public long available() {
        IOStream<?, ?> reader = readers.take();
        try {
            return lock.readInvoke(reader::available);
        } finally {
            readers.offer(reader);
        }
    }

    @Override
    public int read(ByteBuf dst, int length) {
        return ensureRead(reader -> reader.read(dst, length));
    }

    @SneakyThrows
    private int ensureRead(ReadInvoker action) {
        IOStream<?, ?> reader = readers.take();
        try {
            return lock.readInvoke(() -> {
                reader.setPosition(getReaderPosition());
                int read = action.invoke(reader);
                setReaderPosition(reader.getPosition());
                return read;
            });
        } finally {
            readers.offer(reader);
        }
    }

    @Override
    public void write(ByteBuf src, int length) {
        ensureWrite(writer -> writer.write(src, length));
    }

    private void ensureWrite(BiAction<IOStream<?, ?>> action) {
        lock.writeInvoke(() -> {
            writer.setPosition(meta.getLogPosition());
            action.invoke(writer);
            meta.setLogPosition(writer.getPosition());
        });
    }

    @Override
    public void flush() {
        lock.writeInvoke(() -> writer.flush());
    }
}
