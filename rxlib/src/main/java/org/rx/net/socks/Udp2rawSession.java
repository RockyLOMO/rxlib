package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.socket.DatagramPacket;
import lombok.extern.slf4j.Slf4j;
import org.rx.diagnostic.DiagnosticMetrics;
import org.rx.io.Bytes;
import org.rx.net.Sockets;
import org.rx.net.support.UnresolvedEndpoint;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
final class Udp2rawSession {
    private static final String METRIC_PREFIX = "socks.udp2raw";

    final Udp2rawTunnelContext context;
    final Udp2rawSessionKey key;
    final InetSocketAddress clientSource;
    final UnresolvedEndpoint destination;
    final Udp2rawSeqWindow requestWindow = new Udp2rawSeqWindow();
    final AtomicLong responseSeq = new AtomicLong();
    volatile InetSocketAddress udp2rawPeer;
    volatile Channel entryChannel;
    volatile long lastActiveMillis;
    private volatile ChannelFuture natFuture;

    Udp2rawSession(Udp2rawTunnelContext context, Udp2rawSessionKey key,
            InetSocketAddress udp2rawPeer, InetSocketAddress clientSource,
            UnresolvedEndpoint destination, Channel entryChannel) {
        this.context = context;
        this.key = key;
        this.udp2rawPeer = udp2rawPeer;
        this.clientSource = clientSource;
        this.destination = destination;
        this.entryChannel = entryChannel;
        this.lastActiveMillis = System.currentTimeMillis();
    }

    boolean acceptRequestSeq(long seq) {
        return requestWindow.checkAndMark(seq);
    }

    boolean isPeer(InetSocketAddress peer) {
        return samePeer(udp2rawPeer, peer);
    }

    boolean updatePeer(Channel entryChannel, InetSocketAddress udp2rawPeer, boolean allowRebind) {
        InetSocketAddress current = this.udp2rawPeer;
        if (!samePeer(current, udp2rawPeer) && !allowRebind) {
            return false;
        }
        this.entryChannel = entryChannel;
        this.udp2rawPeer = udp2rawPeer;
        this.lastActiveMillis = System.currentTimeMillis();
        context.touch();
        return true;
    }

    void writeToDestination(ByteBuf payload) {
        if (payload == null || !payload.isReadable()) {
            return;
        }
        ChannelFuture future = ensureNatChannel();
        ByteBuf retained = payload.retain();
        if (future.isSuccess()) {
            writeToDestinationNow(future.channel(), retained);
            return;
        }
        future.addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess()) {
                Bytes.release(retained);
                DiagnosticMetrics.record("socks.udp2raw.drop.count", 1D, "reason=nat-bind-fail");
                log.warn("udp2raw nat bind failed tunnel={} connId={}", context.tunnelId, key.getConnId(), f.cause());
                return;
            }
            writeToDestinationNow(f.channel(), retained);
        });
    }

    private void writeToDestinationNow(Channel natChannel, ByteBuf payload) {
        InetSocketAddress target = destination.socketAddress();
        if (target.isUnresolved()) {
            CompletableFuture<InetSocketAddress> resolveFuture = Sockets.resolveUdpEndpointAsync(target,
                    context.manager.server.getConfig());
            resolveFuture.whenComplete((resolved, error) -> natChannel.eventLoop().execute(() -> {
                if (error != null || resolved == null) {
                    Bytes.release(payload);
                    DiagnosticMetrics.record("socks.udp2raw.drop.count", 1D, "reason=bad-dst");
                    log.warn("udp2raw resolve destination {} failed", destination, error);
                    return;
                }
                writeResolvedDestination(natChannel, payload, resolved);
            }));
            return;
        }
        writeResolvedDestination(natChannel, payload, target);
    }

    private void writeResolvedDestination(Channel natChannel, ByteBuf payload, InetSocketAddress target) {
        if (!natChannel.isActive()) {
            Bytes.release(payload);
            DiagnosticMetrics.record("socks.udp2raw.drop.count", 1D, "reason=nat-inactive");
            return;
        }
        DatagramPacket packet = new DatagramPacket(payload, target);
        Sockets.UdpWriteResult result = Sockets.writeUdp(natChannel, packet, METRIC_PREFIX,
                "flow=to-destination");
        if (result != Sockets.UdpWriteResult.ACCEPTED) {
            log.warn("udp2raw drop packet to destination {} result={}", target, result);
        }
    }

    private ChannelFuture ensureNatChannel() {
        ChannelFuture future = natFuture;
        if (future != null) {
            return future;
        }
        synchronized (this) {
            if (natFuture != null) {
                return natFuture;
            }
            SocksConfig config = context.manager.server.getConfig();
            ChannelFuture created = Sockets.udpBootstrap(config, ch -> {
                ChannelPipeline p = ch.pipeline();
                if (config.getUdpReadTimeoutSeconds() > 0 || config.getUdpWriteTimeoutSeconds() > 0) {
                    p.addLast(new ProxyChannelIdleHandler(config.getUdpReadTimeoutSeconds(), config.getUdpWriteTimeoutSeconds()));
                }
                p.addLast(new Udp2rawNatResponseHandler(this));
            }).attr(SocksContext.SOCKS_SVR, context.manager.server).bind(0);
            natFuture = created;
            created.channel().closeFuture().addListener(f -> context.removeSession(key, this, "nat-close"));
            return created;
        }
    }

    void writeToPeer(DatagramPacket response) {
        Channel entry = entryChannel;
        InetSocketAddress peer = udp2rawPeer;
        if (entry == null || peer == null || !entry.isActive()) {
            DiagnosticMetrics.record("socks.udp2raw.drop.count", 1D, "reason=entry-inactive");
            return;
        }
        Udp2rawFrame frame = Udp2rawFrame.data(key.getSessionHi(), key.getSessionLo(), key.getConnId(),
                responseSeq.incrementAndGet());
        int flags = Udp2rawCodec.FLAG_HAS_SRC;
        frame.setSourceAddress(response.sender());
        ByteBuf payload = response.content().retain();
        ByteBuf body = payload;
        ByteBuf compressed = null;
        ByteBuf encoded = null;
        try {
            compressed = Udp2rawPayloadSupport.compress(entry.alloc(), payload, context.compressConfig,
                    context.compressStats, peer, "response");
            if (compressed != null) {
                flags |= Udp2rawCodec.FLAG_COMPRESSED;
                body = compressed;
                payload.release();
                payload = null;
                compressed = null;
            }
            if (Udp2rawPayloadSupport.isRedundantEnabled(context.redundantConfig)) {
                flags |= Udp2rawCodec.FLAG_REDUNDANT;
            }
            frame.setFlags(flags);
            encoded = Udp2rawCodec.encode(entry.alloc(), frame, body);
            if (body == payload) {
                payload = null;
            }
            body = null;
            Sockets.UdpWriteResult result = Udp2rawPayloadSupport.writeEncoded(entry, encoded, peer,
                    context.redundantConfig, context.redundantResolver, "flow=response");
            encoded = null;
            if (result != Sockets.UdpWriteResult.ACCEPTED) {
                log.warn("udp2raw drop response to peer {} result={}", peer, result);
            }
        } catch (Throwable e) {
            if (body == payload) {
                payload = null;
            }
            Bytes.release(body);
            Bytes.release(payload);
            Bytes.release(compressed);
            Bytes.release(encoded);
            throw e;
        }
    }

    void close(String reason) {
        ChannelFuture future = natFuture;
        if (future != null) {
            Sockets.closeOnFlushed(future.channel());
        }
        context.removeSession(key, this, reason);
    }

    private static final class Udp2rawNatResponseHandler extends SimpleChannelInboundHandler<DatagramPacket> {
        final Udp2rawSession session;

        Udp2rawNatResponseHandler(Udp2rawSession session) {
            this.session = session;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket msg) {
            session.writeToPeer(msg);
        }
    }

    private static boolean samePeer(InetSocketAddress a, InetSocketAddress b) {
        if (a == b) {
            return true;
        }
        return a != null && a.equals(b);
    }
}
