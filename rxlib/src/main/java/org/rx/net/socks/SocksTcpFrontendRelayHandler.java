package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.channel.socket.DuplexChannel;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;

import lombok.extern.slf4j.Slf4j;
import org.rx.net.Sockets;
import org.rx.net.TcpBackpressureHandler;

import java.util.ArrayDeque;

@Slf4j
@ChannelHandler.Sharable
public class SocksTcpFrontendRelayHandler extends ChannelInboundHandlerAdapter {
    public static final SocksTcpFrontendRelayHandler DEFAULT = new SocksTcpFrontendRelayHandler();

    private static final AttributeKey<PendingConnect> PENDING = AttributeKey.valueOf("socksTcpPendingConnect");
    static final AttributeKey<HalfCloseWatch> HALF_CLOSE_WATCH = AttributeKey.valueOf("socksTcpHalfCloseWatch");
    private static final long DEFAULT_HALF_CLOSE_IDLE_MILLIS = Math.max(100L,
            Long.getLong("rx.socks.halfCloseIdleMillis", 120_000L));
    static volatile long halfCloseIdleNanos = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(DEFAULT_HALF_CLOSE_IDLE_MILLIS);

    static void setHalfCloseIdleMillisForTest(long millis) {
        halfCloseIdleNanos = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(
                millis <= 0L ? DEFAULT_HALF_CLOSE_IDLE_MILLIS : Math.max(50L, millis));
    }

    static final class HalfCloseWatch implements Runnable {
        final Channel inbound;
        final Channel outbound;
        volatile long lastActiveNano = System.nanoTime();
        io.netty.util.concurrent.ScheduledFuture<?> timer;

        HalfCloseWatch(Channel inbound, Channel outbound) {
            this.inbound = inbound;
            this.outbound = outbound;
        }

        void touch() {
            lastActiveNano = System.nanoTime();
        }

        void start() {
            timer = inbound.eventLoop().schedule(this, halfCloseIdleNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
            inbound.closeFuture().addListener(f -> cancel());
        }

        void cancel() {
            io.netty.util.concurrent.ScheduledFuture<?> t = timer;
            if (t != null) {
                t.cancel(false);
                timer = null;
            }
        }

        private static boolean isBackpressured(Channel inbound, Channel outbound) {
            if (!inbound.isWritable() || !outbound.config().isAutoRead()) {
                return true;
            }
            Channel.Unsafe unsafe = inbound.unsafe();
            ChannelOutboundBuffer buf = unsafe != null ? unsafe.outboundBuffer() : null;
            return buf != null && buf.totalPendingWriteBytes() > 0;
        }

        @Override
        public void run() {
            timer = null;
            if (!inbound.isOpen()) {
                return;
            }
            if (!outbound.isActive()) {
                outbound.close();
                Sockets.closeOnFlushed(inbound);
                return;
            }
            long idleLimit = halfCloseIdleNanos;
            long now = System.nanoTime();
            // Do not mistake downstream slow-client backpressure for an unresponsive backend.
            if (isBackpressured(inbound, outbound)) {
                lastActiveNano = now;
                timer = inbound.eventLoop().schedule(this, idleLimit, java.util.concurrent.TimeUnit.NANOSECONDS);
                return;
            }
            long remaining = idleLimit - (now - lastActiveNano);
            if (remaining <= 0L) {
                outbound.close();
                Sockets.closeOnFlushed(inbound);
                return;
            }
            timer = inbound.eventLoop().schedule(this, remaining, java.util.concurrent.TimeUnit.NANOSECONDS);
        }
    }

    private static final class PendingConnect {
        final ArrayDeque<Object> messages = new ArrayDeque<>();
        int bytes;
        boolean resumeReads, inputShutdown;

        void finish(Channel inbound, ChannelFuture connect) {
            if (!inbound.attr(PENDING).compareAndSet(this, null)) {
                return;
            }
            boolean connected = connect != null && connect.isSuccess()
                    && connect.channel().isActive() && inbound.isActive();
            Object message;
            while ((message = messages.poll()) != null) {
                if (connected) {
                    connect.channel().write(message);
                } else {
                    ReferenceCountUtil.release(message);
                }
            }
            if (connected) {
                Channel outbound = connect.channel();
                outbound.flush();
                if (inputShutdown) {
                    halfCloseOutbound(inbound, outbound);
                } else if (resumeReads && (outbound.isWritable() || outbound.pipeline().get(TcpBackpressureHandler.class) == null)) {
                    Sockets.enableAutoRead(inbound);
                }
            } else if (inputShutdown) {
                inbound.close();
            } else if (resumeReads && inbound.isActive()) {
                Sockets.enableAutoRead(inbound);
            }
        }
    }

    private static void halfCloseOutbound(Channel inbound, Channel outbound) {
        if (outbound == null || !outbound.isActive()) {
            Sockets.closeOnFlushed(inbound);
            return;
        }
        if (!(outbound instanceof DuplexChannel)) {
            Sockets.closeOnFlushed(outbound);
            return;
        }
        HalfCloseWatch watch = new HalfCloseWatch(inbound, outbound);
        if (inbound.attr(HALF_CLOSE_WATCH).compareAndSet(null, watch)) {
            watch.start();
        }
        io.netty.handler.proxy.ProxyHandler proxy = outbound.pipeline().get(io.netty.handler.proxy.ProxyHandler.class);
        if (proxy != null && !proxy.connectFuture().isDone()) {
            proxy.connectFuture().addListener(pf -> {
                if (!pf.isSuccess()) {
                    outbound.close();
                    Sockets.closeOnFlushed(inbound);
                    return;
                }
                if (inbound.eventLoop().inEventLoop()) {
                    flushAndShutdownOutbound(inbound, (DuplexChannel) outbound);
                } else {
                    inbound.eventLoop().execute(() -> flushAndShutdownOutbound(inbound, (DuplexChannel) outbound));
                }
            });
            return;
        }
        flushAndShutdownOutbound(inbound, (DuplexChannel) outbound);
    }

    private static void flushAndShutdownOutbound(Channel inbound, DuplexChannel duplexOutbound) {
        if (!duplexOutbound.isActive() || !inbound.isActive()) {
            duplexOutbound.close();
            Sockets.closeOnFlushed(inbound);
            return;
        }
        duplexOutbound.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess() || !duplexOutbound.isActive() || !inbound.isActive()) {
                duplexOutbound.close();
                Sockets.closeOnFlushed(inbound);
                return;
            }
            duplexOutbound.shutdownOutput().addListener((ChannelFutureListener) sf -> {
                if (!sf.isSuccess() || !duplexOutbound.isActive()) {
                    duplexOutbound.close();
                    Sockets.closeOnFlushed(inbound);
                }
            });
        });
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof ChannelInputShutdownEvent) {
            Channel inbound = ctx.channel();
            PendingConnect pending = inbound.attr(PENDING).get();
            if (pending != null) {
                pending.inputShutdown = true;
                return;
            }
            SocksContext sc = SocksContext.ctx(inbound, false);
            halfCloseOutbound(inbound, sc != null && sc.outbound != null ? sc.outbound.channel() : null);
            return;
        }
        super.userEventTriggered(ctx, evt);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        Channel inbound = ctx.channel();
        SocksContext sc = SocksContext.ctx(inbound);
        SocksUserTraffic.recordWrite(sc, msg);
        PendingConnect pending = inbound.attr(PENDING).get();
        if (pending != null || !sc.isOutboundReady()) {
            boolean first = pending == null;
            if (first) {
                pending = new PendingConnect();
                inbound.attr(PENDING).set(pending);
                pending.resumeReads = inbound.config().isAutoRead();
                Sockets.disableAutoRead(inbound);
            }
            int size = ((ByteBuf) msg).readableBytes();
            int limit = inbound.config().getWriteBufferHighWaterMark();
            if (size > limit - pending.bytes || pending.messages.size() >= 256) {
                ReferenceCountUtil.release(msg);
                inbound.close();
                return;
            }
            pending.messages.add(msg);
            pending.bytes += size;
            if (first) {
                PendingConnect queue = pending;
                sc.outbound.addListener((ChannelFutureListener) f -> {
                    if (inbound.eventLoop().inEventLoop()) {
                        queue.finish(inbound, f);
                    } else {
                        inbound.eventLoop().execute(() -> queue.finish(inbound, f));
                    }
                });
            }
            return;
        }

        Channel outbound = sc.outbound.channel();
        log.debug("TCP RELAY {} => {}[{}]", inbound.remoteAddress(), outbound.localAddress(), outbound.remoteAddress());
        outbound.writeAndFlush(msg);
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        HalfCloseWatch watch = ctx.channel().attr(HALF_CLOSE_WATCH).get();
        if (watch != null) {
            watch.touch();
        }
        super.channelWritabilityChanged(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        Channel inbound = ctx.channel();
        HalfCloseWatch watch = inbound.attr(HALF_CLOSE_WATCH).getAndSet(null);
        if (watch != null) {
            watch.cancel();
        }
        PendingConnect pending = inbound.attr(PENDING).get();
        if (pending != null) {
            pending.finish(inbound, null);
        }
        SocksContext sc = SocksContext.ctx(inbound, false);
        super.channelInactive(ctx);
        if (sc != null && sc.outbound != null) {
            Channel outbound = sc.outbound.channel();
            if (outbound.isActive()) {
                Sockets.closeOnFlushed(outbound);
            } else {
                outbound.close();
            }
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        Channel inbound = ctx.channel();
        SocksContext sc = SocksContext.ctx(inbound, false);
        Channel outbound = sc != null && sc.outbound != null ? sc.outbound.channel() : null;
        log.warn("TCP RELAY {} => {} failed", inbound, outbound, cause);
        Sockets.closeOnFlushed(inbound);
    }
}
