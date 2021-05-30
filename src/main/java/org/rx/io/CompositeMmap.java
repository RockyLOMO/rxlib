package org.rx.io;

import io.netty.buffer.ByteBuf;
import org.rx.bean.DataRange;
import org.rx.bean.Tuple;

import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;

class CompositeMmap {
    final Tuple<MappedByteBuffer, DataRange<Long>>[] composite;

    CompositeMmap(MappedByteBuffer[] buffers) {
        composite = new Tuple[buffers.length];
        long prev = 0;
        for (int i = 0; i < buffers.length; i++) {
            MappedByteBuffer p = buffers[i];
            long len = p.remaining();
            composite[i] = Tuple.of((MappedByteBuffer) p.mark(), new DataRange<>(prev, prev = (prev + len)));
        }
    }

    public void read(long position, ByteBuf byteBuf, long count) {

    }

    public void write(long position, ByteBuf byteBuf) {
        for (Tuple<MappedByteBuffer, DataRange<Long>> tuple : composite) {
            DataRange<Long> range = tuple.right;
            if (!range.has(position)) {
                continue;
            }

            MappedByteBuffer buffer = tuple.left;
            int pos = (int) (position - range.start);
            buffer.position(buffer.reset().position() + pos);
            int len = byteBuf.readableBytes(), limit = buffer.remaining();
            ByteBuf buf = byteBuf;
            int readLen;
            if (limit < len) {
                readLen = limit;
                buf = buf.slice(buf.readerIndex(), readLen);
            } else {
                readLen = len;
            }
            switch (buf.nioBufferCount()) {
                case 0:
                    buffer.put(ByteBuffer.wrap(Bytes.getBytes(buf)));
                    break;
                case 1:
                    buffer.put(buf.nioBuffer());
                    break;
                default:
                    for (ByteBuffer byteBuffer : buf.nioBuffers()) {
                        buffer.put(byteBuffer);
                    }
                    break;
            }
            byteBuf.readerIndex(byteBuf.readerIndex() + readLen);
            if (!byteBuf.isReadable()) {
                return;
            }
        }
    }
}
