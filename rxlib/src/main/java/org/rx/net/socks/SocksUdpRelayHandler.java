package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.AttributeKey;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Tasks;
import org.rx.core.cache.MemoryCache;
import org.rx.io.Bytes;
import org.rx.net.Sockets;
import org.rx.net.socks.upstream.SocksUdpUpstream;
import org.rx.net.socks.upstream.Upstream;
import org.rx.net.support.EndpointTracer;
import org.rx.net.support.UnresolvedEndpoint;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentMap;

/**
 * Per-client UDP relay handler, installed on a dedicated UDP channel created by
 * {@link Socks5CommandRequestHandler} during UDP_ASSOCIATE handshake.
 *
 * Lifecycle: one channel per TCP control connection.
 *
 * Direction logic:
 *   sender is in ctxMap (known upstream)  → upstream response → client  (inbound)
 *   otherwise                              → client → upstream            (outbound)
 *
 * Upstreams are registered on first client packet to each destination,
 * keyed by the exact resolved InetSocketAddress so that response packets
 * (same IP+port) are correctly demultiplexed even when client and upstream
 * share the same IP (e.g. loopback in tests).
 */
@Slf4j
@ChannelHandler.Sharable
public class SocksUdpRelayHandler extends SimpleChannelInboundHandler<DatagramPacket> {
    /** The SOCKS5 client address confirmed by the first UDP packet. */
    public static final AttributeKey<InetSocketAddress> ATTR_CLIENT_ADDR =
            AttributeKey.valueOf("udpClientAddr");

    /**
     * Per-relay context map: upstream InetSocketAddress → SocksContext.
     * Keyed by the resolved upstream destination (IP+port) so inbound
     * responses can be demultiplexed back to the originating client session.
     */
    public static final AttributeKey<ConcurrentMap<InetSocketAddress, SocksContext>> ATTR_CTX_MAP =
            AttributeKey.valueOf("udpCtxMap");

    /**
     * Per-relay route cache: UnresolvedEndpoint (dst) → SocksContext.
     * Prevents re-evaluating routing rules (raiseEvent) for every single UDP packet
     * to the same destination.
     */
    public static final AttributeKey<ConcurrentMap<UnresolvedEndpoint, SocksContext>> ATTR_ROUTE_MAP =
            AttributeKey.valueOf("udpRouteMap");
    static final AttributeKey<ConcurrentMap<UnresolvedEndpoint, RouteInitState>> ATTR_ROUTE_INIT_MAP =
            AttributeKey.valueOf("udpRouteInitMap");
    static final int MAX_PENDING_ROUTE_PACKETS = 32;
    static final int MAX_PENDING_ROUTE_BYTES = 256 * 1024;

    public static final SocksUdpRelayHandler DEFAULT = new SocksUdpRelayHandler();

    static final class PendingPacket {
        final ByteBuf content;
        final InetSocketAddress sender;
        final InetSocketAddress clientOriginAddr;
        final UnresolvedEndpoint destination;

        PendingPacket(ByteBuf content, InetSocketAddress sender, InetSocketAddress clientOriginAddr, UnresolvedEndpoint destination) {
            this.content = content;
            this.sender = sender;
            this.clientOriginAddr = clientOriginAddr;
            this.destination = destination;
        }
    }

    static final class RouteInitState {
        final ArrayDeque<PendingPacket> pendingPackets = new ArrayDeque<>();
        SocksContext context;
        int pendingBytes;

        RouteInitState(SocksContext context) {
            this.context = context;
        }
    }

    /**
     * https://datatracker.ietf.org/doc/html/rfc1928
     * +----+------+------+----------+----------+----------+
     * |RSV | FRAG | ATYP | DST.ADDR | DST.PORT |   DATA   |
     * +----+------+------+----------+----------+----------+
     * | 2  |  1   |  1   | Variable |    2     | Variable |
     * +----+------+------+----------+----------+----------+
     */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket in) throws Exception {
        Channel relay = ctx.channel();
        InetSocketAddress sender = in.sender();

        // If sender is a known upstream address, this is a response from destination.
        // Use exact InetSocketAddress (IP+port) matching so localhost client vs localhost
        // destination are correctly distinguished.
        ConcurrentMap<InetSocketAddress, SocksContext> ctxMap = relay.attr(ATTR_CTX_MAP).get();
        SocksContext upstreamCtx = ctxMap != null ? ctxMap.get(sender) : null;
        if (upstreamCtx != null) {
            handleDestResponse(relay, in, sender, upstreamCtx);
        } else {
            handleClientPacket(relay, in, sender);
        }
    }

    /** Client → Upstream (destination or next-hop SOCKS server) */
    private void handleClientPacket(Channel relay,
                                    DatagramPacket in, InetSocketAddress sender) {
        ByteBuf inBuf = in.content();
        if (inBuf.readableBytes() < 4) {
            return;
        }

        SocksProxyServer server = Sockets.getAttr(relay, SocksContext.SOCKS_SVR);
        SocksConfig config = server.config;

        // Validate RFC1928 header
        int ri = inBuf.readerIndex();
        int rsv = inBuf.getUnsignedShort(ri);
        short frag = inBuf.getUnsignedByte(ri + 2);
        if (rsv != 0 || frag != 0) {
            log.warn("socks5[{}] UDP fragment not supported from {}", config.getListenPort(), sender);
            return;
        }

        // Security: reject packets from non-private IPs unless whitelisted
        InetSocketAddress clientOriginAddr = EndpointTracer.UDP.find(sender);
        if (clientOriginAddr == null) {
            clientOriginAddr = relay.attr(UdpRelayAttributes.ATTR_CLIENT_ORIGIN_ADDR).get();
        }
        if (clientOriginAddr == null) {
            clientOriginAddr = sender;
        }
        relay.attr(UdpRelayAttributes.ATTR_CLIENT_ORIGIN_ADDR).set(clientOriginAddr);

        InetAddress senderIp = clientOriginAddr.getAddress();
        if (config.isWhiteListEnabled() && !config.isAllowed(senderIp)) {
            log.warn("socks5[{}] UDP security error, whiteListSize={} packet from {}",
                    config.getListenPort(), config.getWhiteList().size(), clientOriginAddr);
            return;
        }

        // Confirm / update the client address on first real packet
        InetSocketAddress clientAddr = relay.attr(ATTR_CLIENT_ADDR).get();
        boolean locked = Boolean.TRUE.equals(relay.attr(UdpRelayAttributes.ATTR_CLIENT_LOCKED).get());
        if (locked) {
            if (clientAddr == null || !clientAddr.equals(sender)) {
                return;
            }
        } else if (clientAddr == null || !clientAddr.equals(sender)) {
            relay.attr(ATTR_CLIENT_ADDR).set(sender);
        }
        if (Boolean.TRUE.equals(relay.attr(UdpRelayAttributes.ATTR_REDUNDANT_CLIENT_PEER).get())) {
            UdpRelayAttributes.addRedundantPeer(relay, sender);
        }

        ConcurrentMap<InetSocketAddress, SocksContext> ctxMap = ctxMap(relay);
        ConcurrentMap<UnresolvedEndpoint, SocksContext> routeMap = routeMap(relay);
        ConcurrentMap<UnresolvedEndpoint, RouteInitState> routeInitMap = routeInitMap(relay);

        inBuf.markReaderIndex();
        final UnresolvedEndpoint dstEp = UdpManager.socks5Decode(inBuf);

        SocksContext e = routeMap.get(dstEp);
        if (e != null && isRouteReady(relay, e)) {
            writeClientPacket(relay, inBuf, sender, clientOriginAddr, dstEp, e, false);
            return;
        }

        RouteInitState initState = routeInitMap.get(dstEp);
        boolean created = false;
        if (initState == null) {
            RouteInitState newState = new RouteInitState(e);
            RouteInitState oldState = routeInitMap.putIfAbsent(dstEp, newState);
            initState = oldState != null ? oldState : newState;
            created = oldState == null;
        }
        if (initState.context == null) {
            initState.context = e;
        }
        if (!enqueuePendingPacket(initState, inBuf, sender, clientOriginAddr, dstEp, config)) {
            return;
        }
        if (created) {
            beginRouteInit(relay, server, clientOriginAddr, dstEp, initState, ctxMap, routeMap, routeInitMap);
        }
    }

    /** Upstream response → Client */
    private void handleDestResponse(Channel relay, DatagramPacket in,
                                    InetSocketAddress sender,
                                    SocksContext sc) {
        InetSocketAddress clientAddr = relay.attr(ATTR_CLIENT_ADDR).get();
        if (clientAddr == null) {
            return; // no established session yet
        }

        SocksProxyServer server = Sockets.getAttr(relay, SocksContext.SOCKS_SVR);
        SocksConfig config = server.config;
        ByteBuf outBuf = in.content();

        if (sc != null && sc.getUpstream() instanceof SocksUdpUpstream && ((SocksUdpUpstream) sc.getUpstream()).getUdpRelayAddress(relay) != null) {
            // Response from next-hop SOCKS server: already has SOCKS5 header
            outBuf.retain();
        } else {
            // Direct response: prepend SOCKS5 UDP header with real sender address
            outBuf = UdpManager.socks5Encode(outBuf.retain(), sender);
        }

        if (config.isDebug()) {
            log.info("socks5[{}] UDP IN {}bytes {} => {}",
                    config.getListenPort(), outBuf.readableBytes(), sender, clientAddr);
        }
        relay.writeAndFlush(new DatagramPacket(outBuf, clientAddr));
    }

    private static ConcurrentMap<InetSocketAddress, SocksContext> ctxMap(Channel relay) {
        ConcurrentMap<InetSocketAddress, SocksContext> ctxMap = relay.attr(ATTR_CTX_MAP).get();
        if (ctxMap != null) {
            return ctxMap;
        }
        ConcurrentMap<InetSocketAddress, SocksContext> newMap = MemoryCache.<InetSocketAddress, SocksContext>rootBuilder().maximumSize(256).build().asMap();
        ConcurrentMap<InetSocketAddress, SocksContext> oldMap = relay.attr(ATTR_CTX_MAP).setIfAbsent(newMap);
        return oldMap != null ? oldMap : newMap;
    }

    private static ConcurrentMap<UnresolvedEndpoint, SocksContext> routeMap(Channel relay) {
        ConcurrentMap<UnresolvedEndpoint, SocksContext> routeMap = relay.attr(ATTR_ROUTE_MAP).get();
        if (routeMap != null) {
            return routeMap;
        }
        ConcurrentMap<UnresolvedEndpoint, SocksContext> newMap = MemoryCache.<UnresolvedEndpoint, SocksContext>rootBuilder().maximumSize(256).build().asMap();
        ConcurrentMap<UnresolvedEndpoint, SocksContext> oldMap = relay.attr(ATTR_ROUTE_MAP).setIfAbsent(newMap);
        return oldMap != null ? oldMap : newMap;
    }

    private static ConcurrentMap<UnresolvedEndpoint, RouteInitState> routeInitMap(Channel relay) {
        ConcurrentMap<UnresolvedEndpoint, RouteInitState> routeInitMap = relay.attr(ATTR_ROUTE_INIT_MAP).get();
        if (routeInitMap != null) {
            return routeInitMap;
        }
        ConcurrentMap<UnresolvedEndpoint, RouteInitState> newMap = MemoryCache.<UnresolvedEndpoint, RouteInitState>rootBuilder().maximumSize(256).build().asMap();
        ConcurrentMap<UnresolvedEndpoint, RouteInitState> oldMap = relay.attr(ATTR_ROUTE_INIT_MAP).setIfAbsent(newMap);
        return oldMap != null ? oldMap : newMap;
    }

    private static boolean isRouteReady(Channel relay, SocksContext context) {
        Upstream upstream = context.getUpstream();
        if (!(upstream instanceof SocksUdpUpstream)) {
            return true;
        }
        return ((SocksUdpUpstream) upstream).getUdpRelayAddress(relay) != null;
    }

    private static InetSocketAddress resolveUpstreamTarget(Channel relay, Upstream upstream) {
        if (upstream instanceof SocksUdpUpstream) {
            return ((SocksUdpUpstream) upstream).getUdpRelayAddress(relay);
        }
        return upstream.getDestination().socketAddress();
    }

    private void beginRouteInit(Channel relay, SocksProxyServer server, InetSocketAddress clientOriginAddr,
                                UnresolvedEndpoint dstEp, RouteInitState initState,
                                ConcurrentMap<InetSocketAddress, SocksContext> ctxMap,
                                ConcurrentMap<UnresolvedEndpoint, SocksContext> routeMap,
                                ConcurrentMap<UnresolvedEndpoint, RouteInitState> routeInitMap) {
        Tasks.runAsync(() -> {
            try {
                SocksContext context = initState.context;
                if (context == null) {
                    context = SocksContext.getCtx(clientOriginAddr, dstEp);
                    server.raiseEvent(server.onUdpRoute, context);
                }
                Upstream upstream = context.getUpstream();
                if (upstream == null) {
                    throw new IllegalStateException("UDP route upstream is null for " + dstEp);
                }
                final SocksContext finalContext = context;
                CompletableFuture<Void> readyFuture = upstream.initChannelAsync(relay);
                readyFuture.whenComplete((v, error) -> relay.eventLoop().execute(() -> {
                    if (error != null) {
                        onRouteInitFailure(relay, dstEp, initState, routeMap, routeInitMap, error);
                        return;
                    }
                    onRouteInitSuccess(relay, dstEp, finalContext, initState, ctxMap, routeMap, routeInitMap);
                }));
            } catch (Throwable e) {
                relay.eventLoop().execute(() -> onRouteInitFailure(relay, dstEp, initState, routeMap, routeInitMap, e));
            }
        });
    }

    private void onRouteInitSuccess(Channel relay, UnresolvedEndpoint dstEp, SocksContext context, RouteInitState initState,
                                    ConcurrentMap<InetSocketAddress, SocksContext> ctxMap,
                                    ConcurrentMap<UnresolvedEndpoint, SocksContext> routeMap,
                                    ConcurrentMap<UnresolvedEndpoint, RouteInitState> routeInitMap) {
        if (!routeInitMap.remove(dstEp, initState)) {
            releasePending(initState);
            return;
        }

        InetSocketAddress upDstAddr = resolveUpstreamTarget(relay, context.getUpstream());
        if (upDstAddr == null) {
            releasePending(initState);
            log.warn("socks5[{}] UDP route not ready after async init for {}", Sockets.getAttr(relay, SocksContext.SOCKS_SVR).config.getListenPort(), dstEp);
            return;
        }

        ctxMap.put(upDstAddr, context);
        routeMap.put(dstEp, context);
        flushPending(relay, context, initState);
    }

    private void onRouteInitFailure(Channel relay, UnresolvedEndpoint dstEp, RouteInitState initState,
                                    ConcurrentMap<UnresolvedEndpoint, SocksContext> routeMap,
                                    ConcurrentMap<UnresolvedEndpoint, RouteInitState> routeInitMap,
                                    Throwable error) {
        routeInitMap.remove(dstEp, initState);
        if (initState.context != null) {
            routeMap.remove(dstEp, initState.context);
        }
        releasePending(initState);
        SocksConfig config = Sockets.getAttr(relay, SocksContext.SOCKS_SVR).config;
        log.warn("socks5[{}] UDP async route init fail for {}", config.getListenPort(), dstEp, error);
    }

    private boolean enqueuePendingPacket(RouteInitState initState, ByteBuf inBuf, InetSocketAddress sender,
                                         InetSocketAddress clientOriginAddr, UnresolvedEndpoint dstEp,
                                         SocksConfig config) {
        int bytes = inBuf.readableBytes();
        if (initState.pendingPackets.size() >= MAX_PENDING_ROUTE_PACKETS
                || initState.pendingBytes + bytes > MAX_PENDING_ROUTE_BYTES) {
            log.warn("socks5[{}] UDP pending route overflow for {} sender={}, pendingPackets={}, pendingBytes={}",
                    config.getListenPort(), dstEp, sender, initState.pendingPackets.size(), initState.pendingBytes);
            return false;
        }
        initState.pendingPackets.addLast(new PendingPacket(inBuf.retain(), sender, clientOriginAddr, dstEp));
        initState.pendingBytes += bytes;
        return true;
    }

    private void flushPending(Channel relay, SocksContext context, RouteInitState initState) {
        PendingPacket packet;
        while ((packet = initState.pendingPackets.pollFirst()) != null) {
            initState.pendingBytes -= packet.content.readableBytes();
            writeClientPacket(relay, packet.content, packet.sender, packet.clientOriginAddr, packet.destination, context, true);
        }
        initState.pendingBytes = 0;
    }

    private void releasePending(RouteInitState initState) {
        PendingPacket packet;
        while ((packet = initState.pendingPackets.pollFirst()) != null) {
            Bytes.release(packet.content);
        }
        initState.pendingBytes = 0;
    }

    private void writeClientPacket(Channel relay, ByteBuf inBuf, InetSocketAddress sender,
                                   InetSocketAddress clientOriginAddr, UnresolvedEndpoint dstEp,
                                   SocksContext context, boolean retained) {
        Upstream upstream = context.getUpstream();
        InetSocketAddress upDstAddr = resolveUpstreamTarget(relay, upstream);
        if (upDstAddr == null) {
            if (retained) {
                Bytes.release(inBuf);
            }
            log.warn("socks5[{}] UDP relay not ready for {}, drop packet from {}",
                    Sockets.getAttr(relay, SocksContext.SOCKS_SVR).config.getListenPort(), dstEp, sender);
            return;
        }

        if (upstream instanceof SocksUdpUpstream) {
            inBuf.resetReaderIndex();
        }
        EndpointTracer.UDP.link(clientOriginAddr, relay);
        if (!retained) {
            inBuf.retain();
        }
        SocksConfig config = Sockets.getAttr(relay, SocksContext.SOCKS_SVR).config;
        if (config.isDebug()) {
            log.info("socks5[{}] UDP OUT {}bytes {} => {}[{}]",
                    config.getListenPort(), inBuf.readableBytes(), sender, upDstAddr, dstEp);
        }
        relay.writeAndFlush(new DatagramPacket(inBuf, upDstAddr));
    }
}
