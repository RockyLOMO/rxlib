package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;

/**
 * UDP 单包压缩编码器。
 * <p>
 * 仅在收益达标时输出带 {@code UCMP} 头的压缩包，否则保持透传。
 */
@Slf4j
public class UdpCompressEncoder extends ChannelOutboundHandlerAdapter {
    private final UdpCompressCodec codec;
    private final int minPayloadBytes;
    private final int minSavingsBytes;
    private final double minSavingsRatio;
    private final int compressionLevel;
    private final short dictionaryId;
    private final UdpCompressStats stats;

    public UdpCompressEncoder(UdpCompressConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        if (config.getCodec() != UdpCompressCodec.LZ4_FAST) {
            throw new IllegalArgumentException("unsupported udp compress codec " + config.getCodec());
        }
        if (config.getDictionaryId() != 0) {
            throw new IllegalArgumentException("dictionaryId > 0 is not supported yet");
        }
        this.codec = config.getCodec();
        this.minPayloadBytes = config.getMinPayloadBytes();
        this.minSavingsBytes = config.getMinSavingsBytes();
        this.minSavingsRatio = config.getMinSavingsRatio();
        this.compressionLevel = config.getCompressionLevel();
        this.dictionaryId = config.getDictionaryId();
        this.stats = new UdpCompressStats(config);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (!(msg instanceof DatagramPacket)) {
            super.write(ctx, msg, promise);
            return;
        }

        DatagramPacket original = (DatagramPacket) msg;
        InetSocketAddress recipient = original.recipient();
        if (!UdpRelayAttributes.shouldEncode(ctx.channel(), recipient) || stats.shouldBypass(recipient)) {
            super.write(ctx, msg, promise);
            return;
        }

        ByteBuf payload = original.content();
        int payloadLen = payload.readableBytes();
        if (payloadLen < minPayloadBytes || payloadLen > 0xFFFF) {
            super.write(ctx, msg, promise);
            return;
        }

        DatagramPacket compressedPacket = null;
        try {
            compressedPacket = compress(ctx, original, recipient, payloadLen);
        } catch (Throwable e) {
            log.warn("UDP compress encode fallback to passthrough, codec={}, recipient={}", codec, recipient, e);
        }

        if (compressedPacket == null) {
            stats.recordLowGain(recipient);
            super.write(ctx, msg, promise);
            return;
        }

        try {
            ctx.write(compressedPacket, promise);
            stats.recordApplied(recipient);
        } catch (Throwable e) {
            ReferenceCountUtil.release(compressedPacket);
            throw e;
        } finally {
            ReferenceCountUtil.release(original);
        }
    }

    private DatagramPacket compress(ChannelHandlerContext ctx, DatagramPacket original,
                                    InetSocketAddress recipient, int payloadLen) {
        int maxCompressedLen = UdpCompressSupport.maxCompressedLength(payloadLen, compressionLevel);
        ByteBuf encoded = ctx.alloc().directBuffer(UdpCompressSupport.HEADER_SIZE + maxCompressedLen);
        try {
            encoded.writeInt(UdpCompressSupport.HEADER_MAGIC);
            encoded.writeShort(payloadLen);
            short flags = dictionaryId == 0 ? 0 : UdpCompressSupport.FLAG_DICT;
            encoded.writeByte(flags);
            encoded.writeByte(dictionaryId);

            int dataOffset = encoded.writerIndex();
            int compressedLen = UdpCompressSupport.compress(original.content(), original.content().readerIndex(), payloadLen,
                    encoded, dataOffset, maxCompressedLen, compressionLevel);
            int totalLen = UdpCompressSupport.HEADER_SIZE + compressedLen;
            int savedBytes = payloadLen - totalLen;
            if (savedBytes <= 0) {
                encoded.release();
                return null;
            }
            if (savedBytes < minSavingsBytes) {
                encoded.release();
                return null;
            }
            if (((double) savedBytes / (double) payloadLen) < minSavingsRatio) {
                encoded.release();
                return null;
            }
            encoded.writerIndex(dataOffset + compressedLen);
            return new DatagramPacket(encoded, recipient);
        } catch (Throwable e) {
            encoded.release();
            throw e;
        }
    }
}
