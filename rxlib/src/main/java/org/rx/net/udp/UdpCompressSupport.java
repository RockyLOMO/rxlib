package org.rx.net.udp;

import io.netty.buffer.ByteBuf;

/** Compatibility entry point for the UCMP codec. */
public final class UdpCompressSupport {
    public static final int HEADER_MAGIC = org.rx.net.udp.compress.UdpCompressSupport.HEADER_MAGIC;
    public static final int HEADER_SIZE = org.rx.net.udp.compress.UdpCompressSupport.HEADER_SIZE;
    public static final short FLAG_DICT = org.rx.net.udp.compress.UdpCompressSupport.FLAG_DICT;
    public static final int MAX_COMPRESSION_LEVEL = org.rx.net.udp.compress.UdpCompressSupport.MAX_COMPRESSION_LEVEL;

    private UdpCompressSupport() {
    }

    public static int maxCompressedLength(int srcLen, int compressionLevel) {
        return org.rx.net.udp.compress.UdpCompressSupport.maxCompressedLength(srcLen, compressionLevel);
    }

    public static int compress(ByteBuf src, int srcIndex, int srcLen, ByteBuf dst, int dstIndex, int maxDstLen, int compressionLevel) {
        return org.rx.net.udp.compress.UdpCompressSupport.compress(src, srcIndex, srcLen, dst, dstIndex, maxDstLen, compressionLevel);
    }

    public static int decompress(ByteBuf src, int srcIndex, int srcLen, ByteBuf dst, int dstIndex, int maxDstLen) {
        return org.rx.net.udp.compress.UdpCompressSupport.decompress(src, srcIndex, srcLen, dst, dstIndex, maxDstLen);
    }
}
