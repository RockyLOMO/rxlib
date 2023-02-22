package org.rx.io;

import io.netty.buffer.ByteBuf;
import lombok.Getter;
import lombok.SneakyThrows;
import org.rx.bean.DataRange;
import org.rx.bean.Tuple;
import org.rx.core.Constants;
import org.rx.core.Linq;
import org.rx.util.Lazy;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

public final class CompositeMmap extends IOStream {
    private static final long serialVersionUID = -3293392999599916L;

    private void writeObject(ObjectOutputStream out) throws IOException {
        throw new UnsupportedEncodingException();
    }

    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        throw new UnsupportedEncodingException();
    }

    final FileStream owner;
    @Getter
    final FileStream.Block block;
    final Tuple<MappedByteBuffer, DataRange<Long>>[] buffers;
    long position;
    private transient InputStream reader;
    private transient OutputStream writer;

    @Override
    public String getName() {
        return owner.getName();
    }

    @Override
    public InputStream getReader() {
        if (reader == null) {
            reader = new InputStream() {
                @Override
                public int available() {
                    return safeRemaining(CompositeMmap.this.available());
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    ByteBuf buf = Bytes.wrap(b, off, len);
                    buf.clear();
                    try {
                        int read = CompositeMmap.this.read(position, buf);
                        if (read > -1) {
                            position += read;
                        }
                        return read;
                    } finally {
                        buf.release();
                    }
                }

                @Override
                public int read() {
                    ByteBuf buf = Bytes.directBuffer(1);
                    try {
                        int read = CompositeMmap.this.read(position, buf, 1);
                        if (read == Constants.IO_EOF) {
                            return read;
                        }
                        position += read;
                        return buf.readByte() & 0xff;
                    } finally {
                        buf.release();
                    }
                }
            };
        }
        return reader;
    }

    @Override
    public OutputStream getWriter() {
        if (writer == null) {
            writer = new OutputStream() {
                @Override
                public void write(byte[] b, int off, int len) {
                    ByteBuf buf = Bytes.wrap(b, off, len);
                    try {
                        position += CompositeMmap.this.write(position, buf);
                    } finally {
                        buf.release();
                    }
                }

                @Override
                public void write(int b) {
                    ByteBuf buf = Bytes.directBuffer(1);
                    buf.writeByte(b);
                    try {
                        position += CompositeMmap.this.write(position, buf);
                    } finally {
                        buf.release();
                    }
                }

                @Override
                public void flush() {
                    CompositeMmap.this.flush();
                }
            };
        }
        return writer;
    }

    @Override
    public synchronized long getPosition() {
        return position;
    }

    @Override
    public synchronized void setPosition(long position) {
        this.position = position;
    }

    @Override
    public long getLength() {
        return block.position + block.size;
    }

    public MappedByteBuffer[] buffers() {
        return Linq.from(buffers).select(p -> p.left).toArray();
    }

    @SneakyThrows
    CompositeMmap(FileStream owner, FileChannel.MapMode mode, FileStream.Block block) {
        this.owner = owner;
        this.block = block;

        long totalCount = block.size;
        long max = Integer.MAX_VALUE;
        buffers = new Tuple[(int) Math.floorDiv(totalCount, max) + 1];
        long prev = 0;
        for (int i = 0; i < buffers.length; i++) {
            long count = Math.min(max, totalCount);
            DataRange<Long> range = new DataRange<>(prev, prev = (prev + count));
            buffers[i] = Tuple.of((MappedByteBuffer) owner.getRandomAccessFile().getChannel().map(mode, range.start, count).mark(), range);
            totalCount -= count;
        }
    }

    @Override
    protected void freeObjects() {
        // java.io.IOException: 请求的操作无法在使用用户映射区域打开的文件上执行 (Windows need to run unmap() first)
        // A mapping, once established, is not dependent upon the file channel that was used to create it. Closing the channel, in particular, has no effect upon the validity of the mapping.
        for (Tuple<MappedByteBuffer, DataRange<Long>> tuple : buffers) {
            release(tuple.left);
        }
    }

    @Override
    public long available() {
        return remaining(position);
    }

    public synchronized long remaining(long position) {
        for (Tuple<MappedByteBuffer, DataRange<Long>> tuple : buffers) {
            DataRange<Long> range = tuple.right;
            if (!range.has(position)) {
                continue;
            }
            return range.end - position;
        }
        return 0;
    }

    @Override
    public int read(ByteBuf dst, int length) {
        int read = read(position, dst, length);
        position += read;
        return read;
    }

    public int read(long position, ByteBuf byteBuf) {
        return read(position, byteBuf, byteBuf.writableBytes());
    }

    public synchronized int read(long position, ByteBuf byteBuf, int readCount) {
        checkNotClosed();

        int writerIndex = byteBuf.writerIndex();
        int finalReadCount = readCount;
        Lazy<byte[]> buffer = new Lazy<>(() -> new byte[Math.min(finalReadCount, BufferedRandomAccessFile.MEDIUM_BUF)]);
        for (Tuple<MappedByteBuffer, DataRange<Long>> tuple : buffers) {
            DataRange<Long> range = tuple.right;
            if (!range.has(position)) {
                continue;
            }

            MappedByteBuffer mbuf = tuple.left;
            int pos = (int) (position - range.start);
            mbuf.position(mbuf.reset().position() + pos);
            int count = readCount, limit = mbuf.remaining();
            if (limit < count) {
                count = limit;
            }
            int read = Math.min(count, buffer.getValue().length);
            mbuf.get(buffer.getValue(), 0, read);
            byteBuf.writeBytes(buffer.getValue(), 0, read);

            position += count;
            readCount -= count;
            if (readCount == 0) {
                break;
            }
        }
        int read = byteBuf.writerIndex() - writerIndex;
        return read == 0 ? -1 : read;
    }

    @Override
    public void write(ByteBuf src, int length) {
        position += write(position, src, length);
    }

    public int write(long position, ByteBuf byteBuf) {
        return write(position, byteBuf, byteBuf.readableBytes());
    }

    public synchronized int write(long position, ByteBuf byteBuf, int writeCount) {
        checkNotClosed();

        int readerIndex = byteBuf.readerIndex();
        for (Tuple<MappedByteBuffer, DataRange<Long>> tuple : buffers) {
            DataRange<Long> range = tuple.right;
            if (!range.has(position)) {
                continue;
            }

            MappedByteBuffer mbuf = tuple.left;
            int pos = (int) (position - range.start);
            mbuf.position(mbuf.reset().position() + pos);
            int count = writeCount, limit = mbuf.remaining();
            int rIndex = byteBuf.readerIndex(), rEndIndex = rIndex + count;
            ByteBuf buf = byteBuf;
            if (limit < count) {
                rEndIndex = rIndex + (count = limit);
                buf = buf.slice(rIndex, rEndIndex);
            }
            switch (buf.nioBufferCount()) {
                case 0:
                    mbuf.put(ByteBuffer.wrap(Bytes.getBytes(buf)));
                    break;
                case 1:
                    mbuf.put(buf.nioBuffer());
                    break;
                default:
                    for (ByteBuffer byteBuffer : buf.nioBuffers()) {
                        mbuf.put(byteBuffer);
                    }
                    break;
            }
            byteBuf.readerIndex(rEndIndex);

            position += count;
            writeCount -= count;
            if (writeCount == 0) {
                break;
            }
        }
        return byteBuf.readerIndex() - readerIndex;
    }

    @Override
    public synchronized void flush() {
        for (Tuple<MappedByteBuffer, DataRange<Long>> tuple : buffers) {
            tuple.left.force();
        }
    }
}
