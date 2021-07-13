package org.rx.io;

import io.netty.buffer.ByteBuf;
import io.netty.util.concurrent.FastThreadLocal;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.App;
import org.rx.core.exception.InvalidException;
import org.rx.util.function.BiAction;
import org.rx.util.function.BiFunc;

import java.io.*;
import java.nio.channels.FileChannel;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.rx.core.App.require;

/**
 * Optimum buffer size is related to a number of things: file system block size, CPU cache size and cache latency.
 * <p>
 * Most file systems are configured to use block sizes of 4096 or 8192. In theory, if you configure your buffer size so you are reading a few bytes more than the disk block, the operations with the file system can be extremely inefficient (i.e. if you configured your buffer to read 4100 bytes at a time, each read would require 2 block reads by the file system). If the blocks are already in cache, then you wind up paying the price of RAM -> L3/L2 cache latency. If you are unlucky and the blocks are not in cache yet, the you pay the price of the disk->RAM latency as well.
 * <p>
 * This is why you see most buffers sized as a power of 2, and generally larger than (or equal to) the disk block size. This means that one of your stream reads could result in multiple disk block reads - but those reads will always use a full block - no wasted reads.
 * <p>
 * Now, this is offset quite a bit in a typical streaming scenario because the block that is read from disk is going to still be in memory when you hit the next read (we are doing sequential reads here, after all) - so you wind up paying the RAM -> L3/L2 cache latency price on the next read, but not the disk->RAM latency. In terms of order of magnitude, disk->RAM latency is so slow that it pretty much swamps any other latency you might be dealing with.
 * <p>
 * So, I suspect that if you ran a test with different cache sizes (haven't done this myself), you will probably find a big impact of cache size up to the size of the file system block. Above that, I suspect that things would level out pretty quickly.
 * <p>
 * There are a ton of conditions and exceptions here - the complexities of the system are actually quite staggering (just getting a handle on L3 -> L2 cache transfers is mind bogglingly complex, and it changes with every CPU type).
 * <p>
 * This leads to the 'real world' answer: If your app is like 99% out there, set the cache size to 8192 and move on (even better, choose encapsulation over performance and use BufferedInputStream to hide the details). If you are in the 1% of apps that are highly dependent on disk throughput, craft your implementation so you can swap out different disk interaction strategies, and provide the knobs and dials to allow your users to test and optimize (or come up with some self optimizing system).
 */
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

        private void writeObject(ObjectOutputStream out) throws IOException {
            out.writeLong(_logPosition);
            out.writeInt(size.get());
        }

        private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
            _logPosition = in.readLong();
            size = new AtomicInteger();
            size.set(in.readInt());
        }

        transient BiAction<MetaHeader> writeBack;
        private long _logPosition = HEADER_SIZE;
        private AtomicInteger size = new AtomicInteger();

        public synchronized long getLogPosition() {
            return _logPosition;
        }

        public synchronized void setLogPosition(long logPosition) {
            require(logPosition, logPosition >= HEADER_SIZE);

            _logPosition = logPosition;
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
//            log.debug("write back {}", this);
            writeBack.invoke(this);
        }
    }

    static final float GROW_FACTOR = 0.75f;
    static final int HEADER_SIZE = 256;
    private final FileStream main;
    final CompositeLock lock;
    private final long growSize;
    private final int readerCount;
    private IOStream<?, ?> writer;
    private final LinkedTransferQueue<IOStream<?, ?>> readers = new LinkedTransferQueue<>();
    private final FastThreadLocal<Long> readerPosition = new FastThreadLocal<>();
    private final Serializer serializer;
    final MetaHeader meta;
    private final SequentialWriteQueue writeQueue = new SequentialWriteQueue(8);

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
        return getReaderPosition(false);
    }

    public long getReaderPosition(boolean remove) {
        Long val = readerPosition.getIfExists();
        if (val == null) {
            throw new InvalidException("Reader position not set");
        }
        if (remove) {
            readerPosition.remove();
        }
        return val;
    }

    public void setReaderPosition(long position) {
        readerPosition.set(position);
    }

    @Override
    public long getLength() {
        return lock.readInvoke(main::getLength);
    }

    public WALFileStream(File file, long growSize, int readerCount, Serializer serializer) {
        this.growSize = growSize;
        this.readerCount = readerCount;
        this.serializer = serializer;

        main = new FileStream(file, FileMode.READ_WRITE, BufferedRandomAccessFile.BufSize.NON_BUF);
        lock = main.getLock();
        if (!ensureGrow()) {
            createReaderAndWriter();
        }

        meta = loadMeta();
        meta.writeBack = m -> writeQueue.offer(0, this::saveMeta);
    }

    @Override
    protected void freeObjects() {
        writeQueue.close();
        releaseReaderAndWriter();
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
            writer.setPosition(0);
            serializer.serialize(meta, writer);
            writer.flush();
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
            if (length < growSize) {
                log.debug("growSize {} 0->{}", getName(), growSize);
                _setLength(growSize);
                return true;
            }

            if (meta != null && meta.getLogPosition() / (float) length > GROW_FACTOR) {
                long resize = length + growSize;
                log.debug("growSize {} {}->{}", getName(), length, resize);
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
    private int ensureRead(BiFunc<IOStream<?, ?>, Integer> action) {
        IOStream<?, ?> reader = readers.take();
        try {
            return lock.readInvoke(() -> {
                reader.setPosition(getReaderPosition());
                int read = action.invoke(reader);
                setReaderPosition(reader.getPosition());
                return read;
            }, HEADER_SIZE);
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
        }, HEADER_SIZE);
    }

    @Override
    public void flush() {
        lock.writeInvoke(() -> writer.flush());
    }
}
