package org.rx.io;

import io.netty.buffer.ByteBuf;
import io.netty.util.concurrent.FastThreadLocal;
import lombok.NonNull;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.*;
import org.rx.exception.InvalidException;
import org.rx.util.function.BiAction;
import org.rx.util.function.BiFunc;

import java.io.*;
import java.nio.channels.FileChannel;
import java.util.concurrent.LinkedTransferQueue;

import static org.rx.core.Extends.require;

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
public final class WALFileStream extends IOStream implements EventPublisher<WALFileStream> {
    private static final long serialVersionUID = 1414441456982833443L;

    private void writeObject(ObjectOutputStream out) throws IOException {
        throw new UnsupportedEncodingException();
    }

    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        throw new UnsupportedEncodingException();
    }

    private static class MetaHeader implements Serializable {
        private static final long serialVersionUID = 3894764623767567837L;

        private void writeObject(ObjectOutputStream out) throws IOException {
            out.writeLong(logPos);
            out.writeLong(size);
        }

        private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
            logPos = in.readLong();
            size = in.readLong();
        }

        long logPos = HEADER_SIZE;
        long size;
    }

    static final float GROW_FACTOR = 0.75f;
    static final int HEADER_SIZE = 256;
    static final FastThreadLocal<Long> readerPosition = new FastThreadLocal<>();
    public transient final Delegate<WALFileStream, EventArgs> onGrow = Delegate.create();
    final FileStream file;
    final CompositeLock lock;
    final long growSize;
    final int readerCount;
    private CompositeMmap writer;
    private final LinkedTransferQueue<IOStream> readers = new LinkedTransferQueue<>();
    private final Serializer serializer;
    final MetaHeader meta;
    @Setter
    long flushDelayMillis = 1000;
    private transient InputStream _reader;
    private transient OutputStream _writer;

    @Override
    public String getName() {
        return file.getName();
    }

    @Override
    public InputStream getReader() {
        if (_reader == null) {
            _reader = new InputStream() {
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
        return _reader;
    }

    @Override
    public OutputStream getWriter() {
        if (_writer == null) {
            _writer = new OutputStream() {
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
        return _writer;
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
    public boolean canSeek() {
        return true;
    }

    @Override
    public long getPosition() {
        return lock.readInvoke(() -> meta.logPos);
    }

    @Override
    public void setPosition(long position) {
        require(position, position >= HEADER_SIZE);

        lock.writeInvoke(() -> {
            meta.logPos = position;
            saveMeta();
        }, 0, HEADER_SIZE);
    }

    @Override
    public long getLength() {
        return lock.readInvoke(file::getLength);
    }

    Object extra;

    public long getSize() {
        return lock.readInvoke(() -> meta.size);
    }

    public void setSize(long size) {
        lock.writeInvoke(() -> {
            meta.size = size;
            saveMeta();
        }, 0, HEADER_SIZE);
    }

    public WALFileStream(File file, long growSize, int readerCount, @NonNull Serializer serializer) {
        this.growSize = growSize;
        this.readerCount = readerCount;
        this.serializer = serializer;

        this.file = new FileStream(file, FileMode.READ_WRITE, Constants.NON_BUF);
        lock = this.file.getLock();
        if (!ensureGrow()) {
            createReaderAndWriter();
        }

        meta = loadMeta();
    }

    @Override
    protected void freeObjects() {
        releaseReaderAndWriter();
        file.close();
    }

    public void clear() {
        lock.writeInvoke(() -> {
            meta.size = 0;
            setPosition(HEADER_SIZE);
        });
    }

    void saveMeta() {
//        checkNotClosed();
        lock.writeInvoke(() -> {
            writer.setPosition(0);
            serializer.serialize(meta, writer);
            _flush();
        }, 0, HEADER_SIZE);
    }

    @SneakyThrows
    private MetaHeader loadMeta() {
        IOStream reader = readers.take();
        try {
            return lock.readInvoke(() -> {
                reader.setPosition(0);
                try {
                    return serializer.deserialize(reader, true);
                } catch (Exception e) {
                    if (e instanceof StreamCorruptedException) {
                        log.info("loadMeta {}", e.getMessage());
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
            writer = file.mmap(FileChannel.MapMode.READ_WRITE, 0, length);

            readers.clear();
            for (int i = 0; i < readerCount; i++) {
                readers.add(file.mmap(FileChannel.MapMode.READ_ONLY, 0, length));
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

            IOStream tmp;
            while ((tmp = readers.poll()) != null) {
                tmp.close();
            }
        });
    }

    private boolean ensureGrow() {
        return lock.writeInvoke(() -> {
            long length = file.getLength();
            if (length < growSize || (meta != null && meta.logPos / (float) length > GROW_FACTOR)) {
                long resize = length + growSize;
                log.info("growSize {} {}->{}", getName(), length, resize);
                _setLength(resize);
                raiseEvent(onGrow, EventArgs.EMPTY);
                return true;
            }

            return false;
        });
    }

    private void _setLength(long length) {
        releaseReaderAndWriter();
        file.setLength(length);
        createReaderAndWriter();
    }

    @SneakyThrows
    @Override
    public long available() {
        IOStream reader = readers.take();
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
    private int ensureRead(BiFunc<IOStream, Integer> action) {
        IOStream reader = readers.take();
        try {
            long readerPosition = getReaderPosition();
            return lock.readInvoke(() -> {
                reader.setPosition(readerPosition);
                int read = action.invoke(reader);
                setReaderPosition(reader.getPosition());
                return read;
            }, readerPosition);
        } finally {
            readers.offer(reader);
        }
    }

    @SneakyThrows
    public <T> T readObjectBackwards(BiFunc<IOStream, T> action) {
        IOStream reader = readers.take();
        try {
            long readerPosition = getReaderPosition();
            return lock.readInvoke(() -> {
                reader.setPosition(readerPosition);
                T obj = action.invoke(reader);
                setReaderPosition(reader.getPosition());
                return obj;
            }, HEADER_SIZE, readerPosition);
        } finally {
            readers.offer(reader);
        }
    }

    @Override
    public void write(ByteBuf src, int length) {
        ensureWrite(writer -> writer.write(src, length));
    }

    private void ensureWrite(BiAction<IOStream> action) {
        long logPosition = meta.logPos;
        lock.writeInvoke(() -> {
            if (logPosition != meta.logPos) {
                throw new InvalidException("Concurrent error");
//                log.warn("Fallback lock");
//                lock.writeInvoke(() -> innerWrite(meta.getLogPosition(), action));
//                return;
            }

            innerWrite(logPosition, action);
        }, logPosition);
    }

    void innerWrite(long logPosition, BiAction<IOStream> action) {
        ensureGrow();

        writer.setPosition(logPosition);
        action.accept(writer);
        _flush();

        setPosition(writer.getPosition());
    }

    private void _flush() {
        long delay = flushDelayMillis;
        if (delay <= 0) {
            writer.flush();
            return;
        }
        Tasks.setTimeout(this::flush, delay, writer, Constants.TIMER_SINGLE_FLAG);
    }

    @Override
    public void flush() {
        lock.writeInvoke(() -> writer.flush());
    }
}
