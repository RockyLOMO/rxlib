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
    NONE, ZLIB_STORED, AUTO;

    /** Insert the adaptive frame decoder AFTER this physical-layer handler. */
    public static final String INNER_ANCHOR = "rssEgressInner";
    private static final int PREFACE = 0x52535a01; // RSZ, version 1
    private static final int PREFACE_FIRST_BYTE = (PREFACE >>> 24) & 0xff; // 'R'

    public static RssEgressCodec parse(String name) {
        if (name == null || "none".equals(name)) return NONE;
        if ("zlib-stored".equals(name)) return ZLIB_STORED;
        throw new IllegalArgumentException("Unknown RSS TCP egress codec: " + name);
    }

    public static RssEgressCodec parseServer(String name) {
        if ("auto".equals(name)) return AUTO;
        return parse(name);
    }

    void install(ChannelPipeline pipeline) {
        if (this == NONE) return;
        if (this == AUTO) {
            throw new IllegalStateException("AUTO egress codec is only supported on RSS server");
        }
        installStored(pipeline, null);
    }

    void installServer(ChannelPipeline pipeline) {
        if (this == NONE) return;
        if (this == ZLIB_STORED) {
            installStored(pipeline, null);
            return;
        }
        // Per-connection detection: 'R' (0x52) does not collide with SOCKS5 (0x05), zlib (*8), or RssHopAes (0xa5).
        pipeline.addLast("rssEgressAuto", new ByteToMessageDecoder() {
            @Override protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
                if (!in.isReadable()) return;
                if (in.getUnsignedByte(in.readerIndex()) != PREFACE_FIRST_BYTE) {
                    ctx.pipeline().remove(this);
                    return;
                }
                if (in.readableBytes() < 4) return;
                if (in.readInt() != PREFACE) {
                    in.skipBytes(in.readableBytes());
                    ctx.close();
                    return;
                }
                ctx.writeAndFlush(ctx.alloc().buffer(4, 4).writeInt(PREFACE))
                        .addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
                installStoredHandlersAfter(ctx.pipeline(), ctx.name());
                ctx.pipeline().remove(this);
            }
        });
    }

    private static void installStored(ChannelPipeline pipeline, String unused) {
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
        pipeline.addLast("rssEgressDecoder", newStoredDecoder());
        pipeline.addLast(measure ? "rssEgressEncoder" : INNER_ANCHOR,
                pipeline.channel().isActive() ? newStoredEncoder() : StartEncoder.INSTANCE);
        if (measure) pipeline.addLast(INNER_ANCHOR, new ByteCounter("layer=input"));
    }

    private static void installStoredHandlersAfter(ChannelPipeline pipeline, String anchor) {
        boolean measure = DiagnosticMetrics.isEnabled();
        if (measure) pipeline.addBefore(anchor, "rssEgressWireBytes", new ByteCounter("layer=wire"));
        pipeline.addAfter(anchor, "rssEgressDecoder", newStoredDecoder());
        String encName = measure ? "rssEgressEncoder" : INNER_ANCHOR;
        pipeline.addAfter("rssEgressDecoder", encName, newStoredEncoder());
        if (measure) pipeline.addAfter(encName, INNER_ANCHOR, new ByteCounter("layer=input"));
    }

    private static ChannelHandler newStoredDecoder() {
        return new JdkZlibDecoder(ZlibWrapper.ZLIB, 1 << 20) {
            @Override protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
                try {
                    super.decode(ctx, in, out);
                } catch (Exception malformed) {
                    in.skipBytes(in.readableBytes());
                    ctx.close();
                    throw malformed;
                }
            }
        };
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
