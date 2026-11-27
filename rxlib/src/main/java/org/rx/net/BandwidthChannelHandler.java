package org.rx.net;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.ReferenceCountUtil;

/**
 * Standalone opt-in ChannelDuplexHandler enforcing a shared {@link BandwidthLimiter} on an arbitrary Netty channel.
 * Inbound reads are charged to {@link BandwidthLimiter.Direction#UPLOAD} and outbound writes to
 * {@link BandwidthLimiter.Direction#DOWNLOAD} by default (caller can invert for client-perspective channels).
 * Never auto-installed globally by {@link NetworkFlowControl#install(Channel)}.
 */
public final class BandwidthChannelHandler extends ChannelDuplexHandler {
    private final BandwidthLimiter limiter;
    private final BandwidthLimiter.Direction readDirection;
    private final BandwidthLimiter.Direction writeDirection;
    private TcpBandwidthPacer readPacer;
    private TcpBandwidthPacer writePacer;

    public BandwidthChannelHandler(BandwidthLimiter limiter) {
        this(limiter, BandwidthLimiter.Direction.UPLOAD, BandwidthLimiter.Direction.DOWNLOAD);
    }

    public BandwidthChannelHandler(BandwidthLimiter limiter,
                                   BandwidthLimiter.Direction readDirection,
                                   BandwidthLimiter.Direction writeDirection) {
        if (limiter == null) {
            throw new IllegalArgumentException("limiter is null");
        }
        this.limiter = limiter;
        this.readDirection = readDirection == null ? BandwidthLimiter.Direction.UPLOAD : readDirection;
        this.writeDirection = writeDirection == null ? BandwidthLimiter.Direction.DOWNLOAD : writeDirection;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof DatagramPacket) {
            DatagramPacket packet = (DatagramPacket) msg;
            if (!limiter.tryConsumeUdp(readDirection, packet.content().readableBytes())) {
                ReferenceCountUtil.release(msg);
                return;
            }
            ctx.fireChannelRead(msg);
            return;
        }
        if (msg instanceof ByteBuf) {
            if (!limiter.isEnabled(readDirection) && (readPacer == null || !readPacer.hasPending())) {
                ctx.fireChannelRead(msg);
                return;
            }
            if (readPacer == null) {
                Channel ch = ctx.channel();
                readPacer = new TcpBandwidthPacer(ch, null, readDirection, () -> limiter, ctx::fireChannelRead);
            }
            readPacer.offer((ByteBuf) msg);
            return;
        }
        ctx.fireChannelRead(msg);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg instanceof DatagramPacket) {
            DatagramPacket packet = (DatagramPacket) msg;
            if (!limiter.tryConsumeUdp(writeDirection, packet.content().readableBytes())) {
                ReferenceCountUtil.release(msg);
                promise.trySuccess();
                return;
            }
            ctx.write(msg, promise);
            return;
        }
        if (msg instanceof ByteBuf) {
            if (!limiter.isEnabled(writeDirection) && (writePacer == null || !writePacer.hasPending())) {
                ctx.write(msg, promise);
                return;
            }
            if (writePacer == null) {
                Channel ch = ctx.channel();
                writePacer = new TcpBandwidthPacer(null, ch, writeDirection, () -> limiter, ctx::writeAndFlush);
            }
            boolean accepted = writePacer.offer((ByteBuf) msg);
            if (accepted) {
                promise.trySuccess();
            } else {
                promise.tryFailure(new IllegalStateException("Bandwidth queue overflow"));
            }
            return;
        }
        ctx.write(msg, promise);
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        if (writePacer != null) {
            writePacer.onWritabilityChanged();
        }
        super.channelWritabilityChanged(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        closePacers();
        super.channelInactive(ctx);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        closePacers();
        super.handlerRemoved(ctx);
    }

    private void closePacers() {
        if (readPacer != null) {
            readPacer.close();
            readPacer = null;
        }
        if (writePacer != null) {
            writePacer.close();
            writePacer = null;
        }
    }
}
