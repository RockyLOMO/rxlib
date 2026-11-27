package org.rx.net.rss.tcp;

import io.netty.handler.codec.LengthFieldBasedFrameDecoder;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.channel.socket.DuplexChannel;
import org.rx.diagnostic.DiagnosticMetrics;

import java.net.InetSocketAddress;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Below the RSS transport codecs: both paths carry the original encoded bytes. */
public final class AdaptiveTcpHandler extends ChannelDuplexHandler {
    private final long id;
    private final byte[] key;
    private final boolean server;
    private AdaptiveUdpPort port;
    private final int primaryPort, aliasPort;
    private final int profile, udpFrameLimit;
    private final boolean udpHop;
    private final InetSocketAddress udpTarget;
    private final Consumer<Boolean> udpHealthListener;
    private AdaptiveTcpStream stream;
    private static final long CLOSE_DRAIN_TIMEOUT_MILLIS = Long.getLong("app.rss.adaptive.closeDrainTimeoutMillis", 60_000L);
    private ChannelHandlerContext ctx;
    private ScheduledFuture<?> timer;
    private ScheduledFuture<?> closeDrainTimer;
    private ChannelPromise closePromise;
    private boolean started, closing, udpReady;
    // On the TCP EventLoop. UDP TX counts frames handed to the port, RX counts its authenticated deliveries.
    private long tcpTxBytes, tcpRxBytes, udpTxBytes, udpRxBytes;
    private long tcpTxFrames, tcpRxFrames, udpTxFrames, udpRxFrames;

    public static void install(Channel tcp, long id, byte[] key, AdaptiveUdpPort port, int primary, int alias, int profile) {
        install(tcp, id, key, port, primary, alias, profile, primary != alias, null);
    }

    public static void install(Channel tcp, long id, byte[] key, AdaptiveUdpPort port, int primary, int alias, int profile,
                               boolean udpHop, InetSocketAddress udpTarget) {
        install(tcp, id, key, port, primary, alias, profile, udpHop, udpTarget, 0, null);
    }

    public static void install(Channel tcp, long id, byte[] key, AdaptiveUdpPort port, int primary, int alias, int profile,
                               boolean udpHop, InetSocketAddress udpTarget, int wireMtu) {
        install(tcp, id, key, port, primary, alias, profile, udpHop, udpTarget, wireMtu, null);
    }

    public static void install(Channel tcp, long id, byte[] key, AdaptiveUdpPort port, int primary, int alias, int profile,
                               boolean udpHop, InetSocketAddress udpTarget, int wireMtu,
                               Consumer<Boolean> udpHealthListener) {
        int frameLimit = AdaptiveUdpPort.frameLimit(wireMtu);
        ChannelPipeline pipeline = tcp.pipeline();
        LengthFieldBasedFrameDecoder decoder = new LengthFieldBasedFrameDecoder(
                AdaptiveTcpStream.MAX_TCP_FRAME + 2, 0, 2, 0, 2);
        if (pipeline.context(org.rx.net.RssEgressCodec.INNER_ANCHOR) == null) {
            if (pipeline.context(org.rx.net.socks.RssSocksCarrier.TCP_HANDLER) != null) {
                pipeline.addAfter(org.rx.net.socks.RssSocksCarrier.TCP_HANDLER, "rssAdaptiveLength", decoder);
            } else {
                pipeline.addFirst("rssAdaptiveLength", decoder);
            }
        } else {
            pipeline.addAfter(org.rx.net.RssEgressCodec.INNER_ANCHOR, "rssAdaptiveLength", decoder);
        }
        pipeline.addAfter("rssAdaptiveLength", "rssAdaptive",
                new AdaptiveTcpHandler(id, key, port, primary, alias, profile, udpHop, udpTarget, frameLimit, udpHealthListener));
    }

    public int profile() {
        return profile;
    }

    private AdaptiveTcpHandler(long id, byte[] key, AdaptiveUdpPort port, int primaryPort, int aliasPort, int profile,
                               boolean udpHop, InetSocketAddress udpTarget, int frameLimit,
                               Consumer<Boolean> udpHealthListener) {
        this.udpFrameLimit = frameLimit;
        this.profile = profile;
        this.primaryPort = primaryPort;
        this.aliasPort = aliasPort;
        this.udpHop = udpHop;
        this.udpTarget = udpTarget;
        this.udpHealthListener = udpHealthListener;
        this.id = id;
        this.key = key;
        this.port = port;
        server = port != null;
    }

    @Override public void handlerAdded(ChannelHandlerContext ctx) {
        this.ctx = ctx;
        stream = new AdaptiveTcpStream(ctx.alloc(), this::writeTcp, this::writeUdp,
                ctx::fireChannelRead, System::nanoTime, profile, AdaptiveTcpStream.UDP_TAIL, udpFrameLimit);
        if (ctx.channel().remoteAddress() instanceof InetSocketAddress) {
            InetSocketAddress remote = (InetSocketAddress) ctx.channel().remoteAddress();
            if (remote.getAddress() != null) {
                stream.setPathKey(remote.getAddress().getHostAddress() + (server ? ":s2c" : ":c2s"));
            }
        }
        if (!server && !udpHop && udpTarget != null && udpTarget.isUnresolved()) {
            org.rx.net.Sockets.resolveUdpEndpointAsync(udpTarget, null).whenCompleteAsync((endpoint, error) -> {
                if (closing || !ctx.channel().isActive()) return;
                if (error != null) {
                    DiagnosticMetrics.record("rss.tcp.adaptive.udpUnavailable", 1);
                    notifyUdpHealth(false);
                    return;
                }
                attachUdp(endpoint);
                schedule();
            }, ctx.executor());
            return;
        }
        attachUdp(udpHop ? null : udpTarget);
    }

    private void attachUdp(InetSocketAddress endpoint) {
        try {
            if (server) port.register(id, key, ctx.channel(), udpHop, udpFrameLimit, this::receiveUdp);
            else port = AdaptiveUdpPort.client(id, key, ctx.channel(), primaryPort, aliasPort,
                    endpoint, udpFrameLimit, this::receiveUdp);
        } catch (RuntimeException unavailable) {
            port = null;
            DiagnosticMetrics.record("rss.tcp.adaptive.udpUnavailable", 1);
            notifyUdpHealth(false);
        }
    }

    private void notifyUdpHealth(boolean healthy) {
        if (udpHealthListener != null) {
            udpHealthListener.accept(healthy);
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
            if (closing) {
                // Drain trailing peer ACK/GAP/SACK control frames during half-close so Linux SO_RCVBUF
                // stays empty and does not emit TCP RST while SO_SNDBUF finishes flushing.
                return;
            }
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
            notifyUdpHealth(ready);
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

    public boolean isUdpHopping() {
        return port != null && port.isHopping();
    }

    public InetSocketAddress udpPeerAddress() {
        return port == null ? null : port.peerAddress(id);
    }

    @Override public void close(ChannelHandlerContext ctx, ChannelPromise promise) {
        if (closing || !ctx.channel().isActive()) {
            cancelCloseDrainTimer();
            ChannelPromise pending = closePromise;
            closePromise = null;
            if (pending != null && pending != promise) {
                promise.addListener(f -> {
                    if (f.isSuccess()) pending.trySuccess();
                    else pending.tryFailure(f.cause());
                });
            }
            ctx.close(promise);
            return;
        }
        closing = true;
        if (timer != null) {
            timer.cancel(false);
            timer = null;
        }
        stream.drainToTcp();
        ctx.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(f -> {
            if (!ctx.channel().isOpen()) {
                promise.trySuccess();
                return;
            }
            if (!f.isSuccess() || !ctx.channel().isActive() || tcpTxFrames == 0
                    || !(ctx.channel() instanceof DuplexChannel)
                    || ctx.channel().eventLoop().isShuttingDown()) {
                ctx.close(promise);
                return;
            }
            DuplexChannel duplex = (DuplexChannel) ctx.channel();
            if (duplex.isInputShutdown()) {
                ctx.close(promise);
                return;
            }
            // Half-close write side first so kernel delivers all buffered TCP bytes + FIN while
            // we continue draining any peer ACK/GAP/SACK control frames instead of triggering TCP RST.
            closePromise = promise;
            if (!ctx.channel().config().isAutoRead()) {
                ctx.channel().config().setAutoRead(true);
            }
            duplex.shutdownOutput().addListener(sf -> {
                if (!sf.isSuccess() && ctx.channel().isOpen()) {
                    finishHalfClose(ctx);
                }
            });
            long timeoutMs = Math.max(1000L, CLOSE_DRAIN_TIMEOUT_MILLIS);
            closeDrainTimer = ctx.executor().schedule(() -> {
                closeDrainTimer = null;
                finishHalfClose(ctx);
            }, timeoutMs, TimeUnit.MILLISECONDS);
        });
    }

    @Override public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (closing && evt instanceof ChannelInputShutdownEvent) {
            finishHalfClose(ctx);
            return;
        }
        super.userEventTriggered(ctx, evt);
    }

    @Override public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        cancelCloseDrainTimer();
        ChannelPromise pending = closePromise;
        closePromise = null;
        if (pending != null) {
            pending.trySuccess();
        }
        super.channelInactive(ctx);
    }

    private void finishHalfClose(ChannelHandlerContext ctx) {
        cancelCloseDrainTimer();
        ChannelPromise pending = closePromise;
        closePromise = null;
        if (ctx.channel().isOpen()) {
            ctx.close(pending != null ? pending : ctx.newPromise());
        } else if (pending != null) {
            pending.trySuccess();
        }
    }

    private void cancelCloseDrainTimer() {
        ScheduledFuture<?> t = closeDrainTimer;
        if (t != null) {
            closeDrainTimer = null;
            t.cancel(false);
        }
    }

    private static void recordFrames(String path, long txFrames, long txBytes, long rxFrames, long rxBytes) {
        DiagnosticMetrics.record("rss.tcp.adaptive.tx.frames", txFrames, path);
        DiagnosticMetrics.record("rss.tcp.adaptive.tx.frameBytes", txBytes, path);
        DiagnosticMetrics.record("rss.tcp.adaptive.rx.frames", rxFrames, path);
        DiagnosticMetrics.record("rss.tcp.adaptive.rx.frameBytes", rxBytes, path);
    }

    @Override public void handlerRemoved(ChannelHandlerContext ctx) {
        closing = true;
        if (timer != null) {
            timer.cancel(false);
            timer = null;
        }
        cancelCloseDrainTimer();
        ChannelPromise pending = closePromise;
        closePromise = null;
        if (pending != null) {
            pending.trySuccess();
        }
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
