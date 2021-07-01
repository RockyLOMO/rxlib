package org.rx.io;

import io.netty.buffer.ByteBuf;
import lombok.SneakyThrows;
import org.rx.bean.DataRange;
import org.rx.bean.Tuple;
import org.rx.core.Disposable;
import org.rx.core.Lazy;
import org.rx.core.NQuery;

import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;

public final class CompositeMmap extends Disposable {
    final FileStream owner;
    final FileStream.MapBlock key;
    final Tuple<MappedByteBuffer, DataRange<Long>>[] buffers;

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
