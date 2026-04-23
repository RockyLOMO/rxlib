package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4SafeDecompressor;

import java.nio.ByteBuffer;

/**
 * UDP 压缩公共常量与 LZ4 工具。
 */
final class UdpCompressSupport {
    static final int HEADER_MAGIC = 0x55434D50; // "UCMP"
    static final int HEADER_SIZE = 8;
    static final short FLAG_DICT = 0x01;

    private static final LZ4Factory LZ4 = LZ4Factory.fastestInstance();
    private static final LZ4Compressor COMPRESSOR = LZ4.fastCompressor();
    private static final LZ4SafeDecompressor DECOMPRESSOR = LZ4.safeDecompressor();

    private UdpCompressSupport() {
    }

    static int maxCompressedLength(int srcLen) {
        return COMPRESSOR.maxCompressedLength(srcLen);
    }

    static int compress(ByteBuf src, int srcIndex, int srcLen, ByteBuf dst, int dstIndex, int maxDstLen) {
        BufferRef srcCopy = new BufferRef();
        try {
            ByteBuffer srcBuffer = nioReadableBuffer(src, srcIndex, srcLen, srcCopy);
            ByteBuffer dstBuffer = dst.nioBuffer(dstIndex, maxDstLen);
            return COMPRESSOR.compress(srcBuffer, srcBuffer.position(), srcLen,
                    dstBuffer, dstBuffer.position(), maxDstLen);
        } finally {
            srcCopy.release();
        }
    }

    static int decompress(ByteBuf src, int srcIndex, int srcLen, ByteBuf dst, int dstIndex, int maxDstLen) {
        BufferRef srcCopy = new BufferRef();
        try {
            ByteBuffer srcBuffer = nioReadableBuffer(src, srcIndex, srcLen, srcCopy);
            ByteBuffer dstBuffer = dst.nioBuffer(dstIndex, maxDstLen);
            return DECOMPRESSOR.decompress(srcBuffer, srcBuffer.position(), srcLen,
                    dstBuffer, dstBuffer.position(), maxDstLen);
        } finally {
            srcCopy.release();
        }
    }

    private static ByteBuffer nioReadableBuffer(ByteBuf src, int index, int length, BufferRef copyRef) {
        if (src.nioBufferCount() == 1) {
            return src.nioBuffer(index, length);
        }
        ByteBuf copy = PooledByteBufAllocator.DEFAULT.heapBuffer(length);
        try {
            copy.writeBytes(src, index, length);
            copyRef.buf = copy;
            return copy.nioBuffer(0, length);
        } catch (Throwable e) {
            copy.release();
            throw e;
        }
    }

    private static final class BufferRef {
        private ByteBuf buf;

        private void release() {
            if (buf != null) {
                buf.release();
                buf = null;
            }
        }
    }
}
