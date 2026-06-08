package org.rx.net.udp;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.MessageToMessageDecoder;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * UDP 单包压缩解码器。
 * <p>
 * 只处理带 {@code UCMP} 头的数据报，未命中头部时直接透传。
 */
@Slf4j
public class UdpCompressDecoder extends MessageToMessageDecoder<DatagramPacket> {
    @Override
    protected void decode(ChannelHandlerContext ctx, DatagramPacket msg, List<Object> out) throws Exception {
        ByteBuf content = msg.content();
        int readerIndex = content.readerIndex();
        if (content.readableBytes() < UdpCompressSupport.HEADER_SIZE) {
            out.add(msg.retain());
            return;
        }

        if (content.getInt(readerIndex) != UdpCompressSupport.HEADER_MAGIC) {
            out.add(msg.retain());
            return;
        }

        int originalLen = content.getUnsignedShort(readerIndex + 4);
        short flags = content.getUnsignedByte(readerIndex + 6);
        short dictionaryId = content.getUnsignedByte(readerIndex + 7);
        if ((flags & UdpCompressSupport.FLAG_DICT) != 0 || dictionaryId != 0) {
            log.warn("UDP compress discard packet with unsupported dictionaryId={}, flags={}", dictionaryId, flags);
            return;
        }
        if (originalLen <= 0) {
            log.warn("UDP compress discard packet with invalid originalLen={}", originalLen);
            return;
        }

        int compressedIndex = readerIndex + UdpCompressSupport.HEADER_SIZE;
        int compressedLen = content.readableBytes() - UdpCompressSupport.HEADER_SIZE;
        if (compressedLen <= 0) {
            log.warn("UDP compress discard empty compressed payload from {}", msg.sender());
            return;
        }

        ByteBuf decoded = ctx.alloc().directBuffer(originalLen);
        try {
            int actualLen = UdpCompressSupport.decompress(content, compressedIndex, compressedLen, decoded, 0, originalLen);
            if (actualLen != originalLen) {
                log.warn("UDP compress discard packet due to originalLen mismatch expected={}, actual={}", originalLen, actualLen);
                return;
            }
            decoded.writerIndex(actualLen);
            out.add(new DatagramPacket(decoded, msg.recipient(), msg.sender()));
            decoded = null;
        } catch (Throwable e) {
            log.warn("UDP compress decode failed from {}", msg.sender(), e);
        } finally {
            if (decoded != null) {
                decoded.release();
            }
        }
    }
}
