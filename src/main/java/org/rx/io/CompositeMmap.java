package org.rx.io;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.util.ByteProcessor;
import lombok.RequiredArgsConstructor;
import org.rx.bean.DataRange;
import org.rx.bean.Tuple;
import org.rx.core.Arrays;
import org.rx.core.NQuery;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.GatheringByteChannel;
import java.nio.channels.ScatteringByteChannel;
import java.nio.charset.Charset;

class CompositeMmap {
    static final Tuple<MappedByteBuffer, DataRange<Long>>[] EMPTY = new Tuple[0];
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

    Tuple<MappedByteBuffer, DataRange<Long>>[] sub(long pos, long len) {
        NQuery.of(composite).where(p -> p.r)

        int i = (int) Math.floorDiv(pos, Integer.MAX_VALUE);
        if (i >= composite.length) {
            throw new IndexOutOfBoundsException(String.format("Position %s overflow", pos));
        }
        int c = (int) Math.floorDiv(len, Integer.MAX_VALUE) + 1;
        if (c == 0) {
            throw new IndexOutOfBoundsException(String.format("Position %s overflow", pos));
        }
        return Arrays.subarray(composite, i, i + c);
    }

    public void write(long pos, ByteBuf buf) {
        for (Tuple<MappedByteBuffer, DataRange<Long>> tuple : composite) {
            DataRange<Long> range = tuple.right;
            if (!range.has(pos)) {
                continue;
            }
            MappedByteBuffer buffer = tuple.left;
            int p = (int) (pos - range.start);
            buffer.position(buffer.reset().position() + p);
            if(buffer.remaining()>=buf.readableBytes())
        }


        Tuple<MappedByteBuffer, DataRange<Long>>[] sub = sub(pos, pos + buf.writerIndex());
        if (sub.length == 1) {

        }


        for (Tuple<MappedByteBuffer, DataRange<Long>> tuple : sub) {
            tuple.right.has()
        }
    }
}
