package org.rx.net;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.compression.JdkZlibDecoder;
import io.netty.handler.codec.compression.ZlibWrapper;
import org.rx.net.socks.SocksConfig;

import javax.crypto.spec.SecretKeySpec;
import java.util.List;

/** Selects the RSS business codec before the SOCKS greeting. Only one AES and one zlib layer are allowed. */
final class RssTcpCodecSelector extends ByteToMessageDecoder {
    private final SocksConfig config;
    private final SecretKeySpec key;

    RssTcpCodecSelector(SocksConfig config) {
        this(config, RssHopAes.configuredKey());
    }

    RssTcpCodecSelector(SocksConfig config, SecretKeySpec key) {
        this.config = config;
        this.key = key;
    }

    @Override protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        int pos = in.readerIndex();
        int first = in.getUnsignedByte(pos);
        if (first == 5) {
            ctx.pipeline().remove(this);
            return;
        }
        if (first == RssHopAes.MAGIC && key != null) {
            ctx.pipeline().addAfter(ctx.name(), null, new RssTcpCodecSelector(config, null));
            ctx.pipeline().replace(this, null, new RssHopAes(false, key));
            return;
        }
        if ((first & 0x0f) == 8 && first <= 0x78) {
            if (in.readableBytes() < 2) return;
            int header = in.getUnsignedShort(pos);
            if (header % 31 == 0 && (header & 0x20) == 0) {
                ctx.pipeline().addAfter(ctx.name(), Sockets.ZIP_ENCODER,
                        Sockets.newTcpCompressionEncoder(config, ZlibWrapper.ZLIB));
                ctx.pipeline().replace(this, Sockets.ZIP_DECODER,
                        new JdkZlibDecoder(ZlibWrapper.ZLIB, 1 << 22));
                return;
            }
        }
        in.skipBytes(in.readableBytes());
        ctx.close();
    }
}
