package org.rx.net.rss.tcp;

import io.netty.handler.codec.LengthFieldBasedFrameDecoder;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import org.rx.diagnostic.DiagnosticMetrics;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Below the RSS transport codecs: both paths carry the original encoded bytes. */
public final class AdaptiveTcpHandler extends ChannelDuplexHandler {
    private final long id;
    private final byte[] key;
    private final boolean server;
    private AdaptiveUdpPort port;
    private final int primaryPort, aliasPort;
    private final int profile;
    private AdaptiveStream stream;
    private ChannelHandlerContext ctx;
    private ScheduledFuture<?> timer;
    private boolean started, closing, udpReady;
    // On the TCP EventLoop. UDP TX counts frames handed to the port, RX counts its authenticated deliveries.
    private long tcpTxBytes, tcpRxBytes, udpTxBytes, udpRxBytes;
    private long tcpTxFrames, tcpRxFrames, udpTxFrames, udpRxFrames;

    public static void install(Channel tcp, long id, byte[] key, AdaptiveUdpPort port, int primary, int alias, int profile) {
        tcp.pipeline().addFirst("rssAdaptive", new AdaptiveTcpHandler(id, key, port, primary, alias, profile));
        tcp.pipeline().addFirst("rssAdaptiveLength", new LengthFieldBasedFrameDecoder(
                AdaptiveStream.MAX_TCP_FRAME + 2, 0, 2, 0, 2));
    }

    public int profile() {
        return profile;
    }

    private AdaptiveTcpHandler(long id, byte[] key, AdaptiveUdpPort port, int primaryPort, int aliasPort, int profile) {
        this.profile = profile;
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
                ctx::fireChannelRead, System::nanoTime, profile);
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
        udpTxFrames++;
        udpTxBytes += frame.readableBytes();
        port.send(id, frame);
    }

    private void writeTcp(ByteBuf frame) {
        tcpTxFrames++;
        tcpTxBytes += frame.readableBytes();
        ctx.write(ctx.alloc().buffer(2, 2).writeShort(frame.readableBytes()), ctx.voidPromise());
        ctx.write(frame, ctx.voidPromise());
    }

    private void receiveUdp(ByteBuf frame) {
        try {
            udpRxFrames++;
            udpRxBytes += frame.readableBytes();
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
            tcpRxFrames++;
            tcpRxBytes += frame.readableBytes();
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
        if (delay < 0) {
            if (timer != null) {
                timer.cancel(false);
                timer = null;
            }
            return;
        }
        if (timer != null && timer.getDelay(TimeUnit.MILLISECONDS) <= delay) return;
        if (timer != null) timer.cancel(false);
        timer = ctx.executor().schedule(() -> {
            timer = null;
            if (closing || !ctx.channel().isActive()) return;
            stream.tick();
            ctx.flush();
            schedule();
        }, delay, TimeUnit.MILLISECONDS);
    }

    public boolean usingUdp() {
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

    private static void recordFrames(String path, long txFrames, long txBytes, long rxFrames, long rxBytes) {
        DiagnosticMetrics.record("rss.tcp.adaptive.tx.frames", txFrames, path);
        DiagnosticMetrics.record("rss.tcp.adaptive.tx.frameBytes", txBytes, path);
        DiagnosticMetrics.record("rss.tcp.adaptive.rx.frames", rxFrames, path);
        DiagnosticMetrics.record("rss.tcp.adaptive.rx.frameBytes", rxBytes, path);
    }

    @Override public void handlerRemoved(ChannelHandlerContext ctx) {
        closing = true;
        if (timer != null) timer.cancel(false);
        if (stream != null) stream.close();
        if (DiagnosticMetrics.isEnabled()) {
            recordFrames("path=tcp", tcpTxFrames, tcpTxBytes, tcpRxFrames, tcpRxBytes);
            recordFrames("path=udp", udpTxFrames, udpTxBytes, udpRxFrames, udpRxBytes);
            // LengthFieldBasedFrameDecoder strips this prefix before channelRead.
            DiagnosticMetrics.record("rss.tcp.adaptive.tx.transportBytes", tcpTxBytes + 2 * tcpTxFrames, "path=tcp");
            DiagnosticMetrics.record("rss.tcp.adaptive.rx.transportBytes", tcpRxBytes + 2 * tcpRxFrames, "path=tcp");
        }
        if (port != null && !server) port.close();
        // Server registrations are removed by the TCP close future using peer identity.
    }
}
