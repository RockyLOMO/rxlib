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
                    halfClosePeer(inbound, outbound);
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

    static void halfClosePeer(Channel source, Channel peer) {
        if (peer == null || !peer.isActive()) {
            source.close();
            return;
        }
        if (!(peer instanceof DuplexChannel)) {
            Sockets.closeOnFlushed(peer);
            return;
        }
        DuplexChannel duplexPeer = (DuplexChannel) peer;
        peer.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener((ChannelFutureListener) f -> {
            if (!peer.isActive()) {
                source.close();
                return;
            }
            if (duplexPeer.isInputShutdown()) {
                peer.close();
                source.close();
            } else {
                duplexPeer.shutdownOutput().addListener((ChannelFutureListener) sf -> {
                    if (duplexPeer.isInputShutdown() || !peer.isActive()) {
                        peer.close();
                        source.close();
                    }
                });
            }
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
            halfClosePeer(inbound, sc != null && sc.outbound != null ? sc.outbound.channel() : null);
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
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        Channel inbound = ctx.channel();
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
