package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import org.rx.diagnostic.DiagnosticMetrics;
import org.rx.net.transport.AdaptiveStream;
import org.rx.net.transport.AdaptiveUdpPort;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Below the RSS transport codecs: both paths carry the original encoded bytes. */
final class AdaptiveTcpHandler extends ChannelDuplexHandler {
    private final long id;
    private final byte[] key;
    private final boolean server;
    private AdaptiveUdpPort port;
    private final int primaryPort, aliasPort;
    private AdaptiveStream stream;
    private ChannelHandlerContext ctx;
    private ScheduledFuture<?> timer;
    private boolean started, closing, udpReady;
    private long tcpFrameBytes, udpFrameBytes;

    AdaptiveTcpHandler(long id, byte[] key, AdaptiveUdpPort port, int primaryPort, int aliasPort) {
        this.primaryPort = primaryPort;
        this.aliasPort = aliasPort;
        this.id = id;
        this.key = key;
        this.port = port;
        server = port != null;
    }

    @Override public void handlerAdded(ChannelHandlerContext ctx) {
        this.ctx = ctx;
        stream = new AdaptiveStream(ctx.alloc(), this::writeTcp, this::writeUdp,
                ctx::fireChannelRead, System::nanoTime);
        try {
            if (server) port.register(id, key, ctx.channel(), this::receiveUdp);
            else port = AdaptiveUdpPort.client(id, key, ctx.channel(), primaryPort, aliasPort, this::receiveUdp);
        } catch (RuntimeException unavailable) {
            port = null;
            DiagnosticMetrics.record("rss.tcp.adaptive.udpUnavailable", 1);
        }
    }

    private void writeUdp(ByteBuf frame) {
        if (port == null) {
            frame.release();
            return;
        }
        udpFrameBytes += frame.readableBytes();
        port.send(id, frame);
    }

    private void writeTcp(ByteBuf frame) {
        tcpFrameBytes += frame.readableBytes();
        ctx.write(ctx.alloc().buffer(2, 2).writeShort(frame.readableBytes()), ctx.voidPromise());
        ctx.write(frame, ctx.voidPromise());
    }

    private void receiveUdp(ByteBuf frame) {
        try {
            if (!started || closing || !ctx.channel().config().isAutoRead()) return;
            stream.udp(frame);
            ctx.fireChannelReadComplete();
            ctx.flush();
            schedule();
        } catch (RuntimeException e) {
            ctx.fireExceptionCaught(e);
            ctx.close();
        } finally {
            frame.release();
        }
    }

    @Override public void channelRead(ChannelHandlerContext ctx, Object msg) {
        ByteBuf frame = (ByteBuf) msg;
        try {
            started = true;
            stream.tcp(frame);
            ctx.flush();
            schedule();
        } catch (RuntimeException e) {
            ctx.close();
            throw e;
        } finally {
            frame.release();
        }
    }

    @Override public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        if (!(msg instanceof ByteBuf)) {
            ctx.write(msg, promise);
            return;
        }
        ByteBuf bytes = (ByteBuf) msg;
        try {
            if (!bytes.isReadable()) {
                stream.drainToTcp();
            } else {
                started = true;
                stream.write(bytes);
            }
            // Complete the caller's promise after previously enqueued TCP frames (including repairs).
            ctx.write(Unpooled.EMPTY_BUFFER, promise);
            schedule();
        } catch (RuntimeException e) {
            promise.tryFailure(e);
            ctx.close();
        } finally {
            bytes.release();
        }
    }

    private void schedule() {
        if (!started || closing || port == null) return;
        boolean ready = stream.usingUdp();
        if (ready != udpReady) {
            udpReady = ready;
            DiagnosticMetrics.record("rss.tcp.adaptive.transition", 1, ready ? "path=udp" : "path=tcp");
        }
        long delay = stream.nextDelayMillis();
        if (delay < 0 || timer != null && timer.getDelay(TimeUnit.MILLISECONDS) <= delay) return;
        if (timer != null) timer.cancel(false);
        timer = ctx.executor().schedule(() -> {
            timer = null;
            if (closing || !ctx.channel().isActive()) return;
            stream.tick();
            ctx.flush();
            schedule();
        }, delay, TimeUnit.MILLISECONDS);
    }

    boolean usingUdp() {
        return stream.usingUdp();
    }

    @Override public void close(ChannelHandlerContext ctx, ChannelPromise promise) {
        if (closing || !ctx.channel().isActive()) {
            ctx.close(promise);
            return;
        }
        closing = true;
        stream.drainToTcp();
        ctx.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(f -> ctx.close(promise));
    }

    @Override public void handlerRemoved(ChannelHandlerContext ctx) {
        closing = true;
        if (timer != null) timer.cancel(false);
        if (stream != null) stream.close();
        if (DiagnosticMetrics.isEnabled()) {
            DiagnosticMetrics.record("rss.tcp.adaptive.tx.frameBytes", tcpFrameBytes, "path=tcp");
            DiagnosticMetrics.record("rss.tcp.adaptive.tx.frameBytes", udpFrameBytes, "path=udp");
        }
        if (port != null && !server) port.close();
        // Server registrations are removed by the TCP close future using peer identity.
    }
}
