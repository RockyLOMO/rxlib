package org.rx.net.udp;

import io.netty.buffer.ByteBuf;
import net.jpountz.lz4.LZ4Exception;

import java.util.concurrent.TimeUnit;

/** Per-association, event-loop confined. Raw SOCKS5 datagrams start with two reserved zero bytes.
 * A nonzero prefix in the negotiated profile gives the length of one independent LZ4 block's output. */
final class DailyUdpCompression {
    private static final int MIN_PAYLOAD = 96, PREFIX = 2;
    private static final long BYPASS_NANOS = TimeUnit.SECONDS.toNanos(30);
    private int lowGain;
    private long bypassStarted;

    /** Borrows the input. Returns an owned block, or null to send the original unchanged. */
    ByteBuf tryEncode(ByteBuf input, long now) {
        int len = input.readableBytes();
        if (len < MIN_PAYLOAD || lowGain == 8 && now - bypassStarted < BYPASS_NANOS) {
            return null;
        }
        int max = UdpCompressSupport.maxCompressedLength(len, 0);
        ByteBuf block = input.alloc().ioBuffer(PREFIX + max, PREFIX + max);
        try {
            int compressed = UdpCompressSupport.compress(input, input.readerIndex(), len, block, PREFIX, max, 0);
            int saved = len - PREFIX - compressed;
            if (saved < 24 || saved * 100 < len * 12) {
                if (lowGain < 8) {
                    lowGain++;
                }
                if (lowGain == 8) {
                    bypassStarted = now;
                }
                return null;
            }
            lowGain = 0;
            block.setShort(0, len).writerIndex(PREFIX + compressed);
            ByteBuf result = block;
            block = null;
            return result;
        } finally {
            if (block != null) {
                block.release();
            }
        }
    }

    /** Takes ownership, including malformed blocks. Raw and recovered packets use the same path. */
    static ByteBuf decode(ByteBuf input, int limit) {
        int size = input.readableBytes(), pos = input.readerIndex();
        if (size >= PREFIX && size <= limit && input.getUnsignedShort(pos) == 0) {
            return input;
        }
        try {
            if (size <= PREFIX) {
                return null;
            }
            int len = input.getUnsignedShort(pos);
            if (len < MIN_PAYLOAD || len > limit || size >= len) {
                return null;
            }
            ByteBuf decoded = input.alloc().ioBuffer(len, len);
            try {
                int actual = UdpCompressSupport.decompress(input, pos + PREFIX, size - PREFIX, decoded, 0, len);
                if (actual != len) {
                    return null;
                }
                decoded.writerIndex(len);
                ByteBuf result = decoded;
                decoded = null;
                return result;
            } catch (LZ4Exception ignored) {
                return null;
            } finally {
                if (decoded != null) {
                    decoded.release();
                }
            }
        } finally {
            input.release();
        }
    }
}
