package org.rx.net.transport.hybrid;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.MessageToByteEncoder;
import io.netty.handler.codec.MessageToMessageDecoder;
import org.rx.io.Bytes;
import org.rx.net.Sockets;
import org.rx.net.transport.FuryUdpClientCodec;
import org.rx.net.transport.TcpChannelCodec;
import org.rx.net.transport.UdpClientCodec;

import java.util.List;

public final class HybridTcpChannelCodec implements TcpChannelCodec {
    private static final long serialVersionUID = -5928419974183329095L;

    private final UdpClientCodec codec;

    public HybridTcpChannelCodec() {
        this(FuryUdpClientCodec.createDefault());
    }

    public HybridTcpChannelCodec(UdpClientCodec codec) {
        this.codec = codec == null ? FuryUdpClientCodec.createDefault() : codec;
    }

    @Override
    public void install(ChannelPipeline pipeline) {
        pipeline.addLast(Sockets.intLengthFieldDecoder(),
                new Decoder(codec),
                Sockets.INT_LENGTH_FIELD_ENCODER,
                new Encoder(codec));
    }

    static final class Encoder extends MessageToByteEncoder<Object> {
        private final UdpClientCodec codec;

        Encoder(UdpClientCodec codec) {
            this.codec = codec;
        }

        @Override
        protected void encode(ChannelHandlerContext ctx, Object msg, ByteBuf out) throws Exception {
            ByteBuf payload = null;
            try {
                payload = codec.encode(ctx.alloc(), msg);
                out.writeBytes(payload, payload.readerIndex(), payload.readableBytes());
            } finally {
                Bytes.release(payload);
            }
        }
    }

    static final class Decoder extends MessageToMessageDecoder<ByteBuf> {
        private final UdpClientCodec codec;

        Decoder(UdpClientCodec codec) {
            this.codec = codec;
        }

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf msg, List<Object> out) throws Exception {
            out.add(codec.decode(msg));
        }
    }
}
