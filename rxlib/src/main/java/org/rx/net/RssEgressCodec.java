package org.rx.net;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.compression.JdkZlibDecoder;
import io.netty.handler.codec.compression.ZlibCodecFactory;
import io.netty.handler.codec.compression.ZlibWrapper;
import org.rx.diagnostic.DiagnosticMetrics;

import java.util.List;

/** Physical RSS TCP stream format. The UDP fast path never visits these handlers. */
public enum RssEgressCodec {
    NONE, ZLIB_STORED;

    /** Insert the adaptive frame decoder AFTER this physical-layer handler. */
    public static final String INNER_ANCHOR = "rssEgressInner";
    private static final int PREFACE = 0x52535a01; // RSZ, version 1

    public static RssEgressCodec parse(String name) {
        if (name == null || "none".equals(name)) return NONE;
        if ("zlib-stored".equals(name)) return ZLIB_STORED;
        throw new IllegalArgumentException("Unknown RSS TCP egress codec: " + name);
    }

    void install(ChannelPipeline pipeline) {
        if (this == NONE) return;
        boolean measure = DiagnosticMetrics.isEnabled();
        if (measure) pipeline.addLast("rssEgressWireBytes", new ByteCounter("layer=wire"));
        pipeline.addLast("rssEgressPreface", new ByteToMessageDecoder() {
            boolean sent;

            private void send(ChannelHandlerContext ctx) {
                if (!sent) {
                    sent = true;
                    ctx.writeAndFlush(ctx.alloc().buffer(4, 4).writeInt(PREFACE))
                            .addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
                }
            }

            @Override public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
                super.handlerAdded(ctx);
                if (ctx.channel().isActive()) send(ctx);
            }

            @Override public void channelActive(ChannelHandlerContext ctx) throws Exception {
                send(ctx);
                super.channelActive(ctx);
            }

            @Override protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
                if (in.readableBytes() < 4) return;
                if (in.readInt() != PREFACE) {
                    in.skipBytes(in.readableBytes());
                    ctx.close();
                    return;
                }
                ctx.pipeline().remove(this);
            }
        });
        pipeline.addLast("rssEgressDecoder", new JdkZlibDecoder(ZlibWrapper.ZLIB, 1 << 20) {
            @Override protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
                try {
                    super.decode(ctx, in, out);
                } catch (Exception malformed) {
                    in.skipBytes(in.readableBytes());
                    ctx.close();
                    throw malformed;
                }
            }
        });
        pipeline.addLast(measure ? "rssEgressEncoder" : INNER_ANCHOR,
                pipeline.channel().isActive() ? newStoredEncoder() : StartEncoder.INSTANCE);
        if (measure) pipeline.addLast(INNER_ANCHOR, new ByteCounter("layer=input"));
    }

    private static ChannelHandler newStoredEncoder() {
        return ZlibCodecFactory.newZlibEncoder(ZlibWrapper.ZLIB, 0);
    }

    /** A failed connect must not instantiate a deflater and flush a trailer to an inactive channel. */
    @ChannelHandler.Sharable
    private static final class StartEncoder extends ChannelInboundHandlerAdapter {
        static final StartEncoder INSTANCE = new StartEncoder();

        @Override public void channelActive(ChannelHandlerContext ctx) {
            ctx.pipeline().replace(this, ctx.name(), newStoredEncoder());
            ctx.fireChannelActive();
        }
    }

    /** The counters include the preface, flush blocks and trailer at the wire boundary. Emit once at close. */
    private static final class ByteCounter extends ChannelDuplexHandler {
        private final String layer;
        private long inbound;
        private long outbound;

        ByteCounter(String layer) {
            this.layer = layer;
        }

        @Override public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof ByteBuf) inbound += ((ByteBuf) msg).readableBytes();
            ctx.fireChannelRead(msg);
        }

        @Override public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            if (msg instanceof ByteBuf) outbound += ((ByteBuf) msg).readableBytes();
            ctx.write(msg, promise);
        }

        @Override public void handlerRemoved(ChannelHandlerContext ctx) {
            DiagnosticMetrics.record("rss.tcp.egress.rx.bytes", inbound, layer);
            DiagnosticMetrics.record("rss.tcp.egress.tx.bytes", outbound, layer);
        }
    }
}
