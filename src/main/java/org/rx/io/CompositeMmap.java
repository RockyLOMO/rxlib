package org.rx.io;

import io.netty.buffer.ByteBuf;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import org.rx.bean.DataRange;
import org.rx.bean.Tuple;
import org.rx.core.Lazy;
import org.rx.core.NQuery;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;

public final class CompositeMmap extends IOStream<InputStream, OutputStream> {
    final FileStream owner;
    final FileStream.MapBlock key;
    final Tuple<MappedByteBuffer, DataRange<Long>>[] buffers;
    @Getter
    @Setter
    long position;

    @Override
    public boolean canSeek() {
        return true;
    }

    @SneakyThrows
    @Override
    public long getLength() {
        return key.size;
    }

    @Override
    public String getName() {
        return owner.getName();
    }

    @Override
    public InputStream getReader() {
        if (reader == null) {
            reader = new InputStream() {
                @Override
                public int read(byte[] b, int off, int len) {
                    ByteBuf buf = Bytes.wrap(b, off, len);
                    buf.clear();
                    try {
                        int read = CompositeMmap.this.read(position, buf);
                        position += read;
                        return read;
                    } finally {
                        buf.release();
                    }
                }

                @Override
                public int read() {
                    ByteBuf buf = Bytes.directBuffer();
                    try {
                        position += CompositeMmap.this.read(position, buf, 1);
                        return buf.readByte();
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
                    ByteBuf buf = Bytes.directBuffer();
                    buf.writeByte(b);
                    try {
                        position += CompositeMmap.this.write(position, buf);
                    } finally {
                        buf.release();
                    }
                }

                @Override
                public void flush() {
                    for (Tuple<MappedByteBuffer, DataRange<Long>> tuple : buffers) {
                        tuple.left.force();
                    }
                }
            };
        }
        return writer;
    }

    public long position() {
        return key.position;
    }

    public long size() {
        return key.size;
    }

    public MappedByteBuffer[] buffers() {
        return NQuery.of(buffers).select(p -> p.left).toArray();
    }

    @SneakyThrows
    CompositeMmap(FileStream owner, FileStream.MapBlock key) {
        super(null, null);
        this.owner = owner;
        this.key = key;

        long totalCount = key.size;
        long max = Integer.MAX_VALUE;
        buffers = new Tuple[(int) Math.floorDiv(totalCount, max) + 1];
        long prev = 0;
        for (int i = 0; i < buffers.length; i++) {
            long count = Math.min(max, totalCount);
            DataRange<Long> range = new DataRange<>(prev, prev = (prev + count));
            buffers[i] = Tuple.of((MappedByteBuffer) owner.getRandomAccessFile().getChannel().map(key.mode, range.start, count).mark(), range);
            totalCount -= count;
        }
    }

    @Override
    protected void freeObjects() {
        owner.unmap(this);
    }

    public int read(long position, ByteBuf byteBuf) {
        return read(position, byteBuf, byteBuf.writableBytes());
    }

    public synchronized int read(long position, ByteBuf byteBuf, int readCount) {
        checkNotClosed();

        int writerIndex = byteBuf.writerIndex();
        int finalReadCount = readCount;
        Lazy<byte[]> buffer = new Lazy<>(() -> new byte[Math.min(finalReadCount, BufferedRandomAccessFile.BufSize.SMALL_DATA.value)]);
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
        return byteBuf.writerIndex() - writerIndex;
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
}
