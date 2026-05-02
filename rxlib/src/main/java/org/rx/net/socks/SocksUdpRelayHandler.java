package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.AttributeKey;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Tasks;
import org.rx.core.cache.MemoryCache;
import org.rx.diagnostic.DiagnosticMetrics;
import org.rx.io.Bytes;
import org.rx.net.Sockets;
import org.rx.net.socks.upstream.SocksUdpUpstream;
import org.rx.net.socks.upstream.Upstream;
import org.rx.net.support.EndpointTracer;
import org.rx.net.support.UnresolvedEndpoint;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentMap;

/**
 * Per-client UDP relay handler, installed on a dedicated UDP channel created by
 * {@link Socks5CommandRequestHandler} during UDP_ASSOCIATE handshake.
 *
 * Lifecycle: one channel per TCP control connection.
 *
 * Direction logic:
 * sender is in ctxMap (known upstream) → upstream response → client (inbound)
 * otherwise → client → upstream (outbound)
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
     * Prevents re-evaluating routing rules (publishEvent) for every single UDP packet
     * to the same destination.
     */
    public static final AttributeKey<ConcurrentMap<UnresolvedEndpoint, SocksContext>> ATTR_ROUTE_MAP =
            AttributeKey.valueOf("udpRouteMap");
    static final AttributeKey<ConcurrentMap<UnresolvedEndpoint, RouteInitState>> ATTR_ROUTE_INIT_MAP =
            AttributeKey.valueOf("udpRouteInitMap");
    static final AttributeKey<LastRoute> ATTR_LAST_ROUTE = AttributeKey.valueOf("udpLastRoute");
    static final AttributeKey<MemoryCache<UnresolvedEndpoint, UdpManager.HeaderTemplate>> ATTR_ROUTE_HEADER_CACHE =
            AttributeKey.valueOf("udpRouteHeaderCache");
    static final AttributeKey<ConcurrentMap<UnresolvedEndpoint, InetSocketAddress>> ATTR_DIRECT_TARGET_MAP =
            AttributeKey.valueOf("udpDirectTargetMap");
    static final AttributeKey<UdpMetricTagCache> ATTR_UDP_METRIC_TAG_CACHE =
            AttributeKey.valueOf("udpMetricTagCache");
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

    static final class LastRoute {
        final UdpManager.HeaderTemplate requestHeader;
        final SocksContext context;

        LastRoute(UdpManager.HeaderTemplate requestHeader, SocksContext context) {
            this.requestHeader = requestHeader;
            this.context = context;
        }

        boolean matches(ByteBuf buf) {
            return requestHeader.matches(buf);
        }
    }

    static final class UdpMetricTagCache {
        static final String TO_CLIENT = "to-client";
        static final String TO_UPSTREAM = "to-upstream";
        String toClient;
        String toClientSocks;
        String toClientDirect;
        String toUpstream;
        String toUpstreamSocks;
        String toUpstreamDirect;

        String tags(boolean toClientDirection, Upstream upstream) {
            if (upstream instanceof SocksUdpUpstream) {
                return toClientDirection ? toClientSocks() : toUpstreamSocks();
            }
            if (upstream != null) {
                return toClientDirection ? toClientDirect() : toUpstreamDirect();
            }
            return toClientDirection ? toClient() : toUpstream();
        }

        private String toClient() {
            if (toClient == null) {
                toClient = base(TO_CLIENT);
            }
            return toClient;
        }

        private String toClientSocks() {
            if (toClientSocks == null) {
                toClientSocks = base(TO_CLIENT) + ",mode=socks";
            }
            return toClientSocks;
        }

        private String toClientDirect() {
            if (toClientDirect == null) {
                toClientDirect = base(TO_CLIENT) + ",mode=direct";
            }
            return toClientDirect;
        }

        private String toUpstream() {
            if (toUpstream == null) {
                toUpstream = base(TO_UPSTREAM);
            }
            return toUpstream;
        }

        private String toUpstreamSocks() {
            if (toUpstreamSocks == null) {
                toUpstreamSocks = base(TO_UPSTREAM) + ",mode=socks";
            }
            return toUpstreamSocks;
        }

        private String toUpstreamDirect() {
            if (toUpstreamDirect == null) {
                toUpstreamDirect = base(TO_UPSTREAM) + ",mode=direct";
            }
            return toUpstreamDirect;
        }

        private String base(String direction) {
            return "path=relay,flow=" + direction;
        }
    }

    /**
     * https://datatracker.ietf.org/doc/html/rfc1928
     * +----+------+------+----------+----------+----------+
     * |RSV | FRAG | ATYP | DST.ADDR | DST.PORT | DATA |
     * +----+------+------+----------+----------+----------+
     * | 2 | 1 | 1 | Variable | 2 | Variable |
     * +----+------+------+----------+----------+----------+
     */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket in) throws Exception {
        Channel relay = ctx.channel();
        UdpRelayGroupManager.touch(relay);
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
            recordDrop("invalid-header", sender, null, config, inBuf.readableBytes());
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
            recordDrop("whitelist-deny", sender, clientOriginAddr, config, inBuf.readableBytes());
            log.warn("socks5[{}] UDP security error, whiteListSize={} packet from {}",
                    config.getListenPort(), config.getWhiteList().size(), clientOriginAddr);
            return;
        }

        // Confirm / update the client address on first real packet
        InetSocketAddress clientAddr = relay.attr(ATTR_CLIENT_ADDR).get();
        boolean locked = Boolean.TRUE.equals(relay.attr(UdpRelayAttributes.ATTR_CLIENT_LOCKED).get());
        if (locked) {
            if (clientAddr == null || !clientAddr.equals(sender)) {
                recordDrop("unexpected-client", sender, clientOriginAddr, config, inBuf.readableBytes());
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
        LastRoute lastRoute = relay.attr(ATTR_LAST_ROUTE).get();
        if (lastRoute != null && lastRoute.matches(inBuf)) {
            SocksContext lastContext = lastRoute.context;
            if (isRouteReady(relay, lastContext)) {
                if (!(lastContext.getUpstream() instanceof SocksUdpUpstream)) {
                    inBuf.skipBytes(lastRoute.requestHeader.length());
                }
                writeClientPacket(relay, inBuf, sender, clientOriginAddr, lastContext.getFirstDestination(), lastContext, false);
                return;
            }
            relay.attr(ATTR_LAST_ROUTE).set(null);
        }
        final UnresolvedEndpoint dstEp = UdpManager.socks5Decode(inBuf);

        SocksContext e = routeMap.get(dstEp);
        if (e != null && isRouteReady(relay, e)) {
            relay.attr(ATTR_LAST_ROUTE).set(new LastRoute(routeHeaderTemplate(relay, dstEp), e));
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

        boolean socksUpstreamResponse = sc != null && sc.getUpstream() instanceof SocksUdpUpstream
                && ((SocksUdpUpstream) sc.getUpstream()).getUdpRelayAddress(relay) != null;
        if (socksUpstreamResponse) {
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
        DatagramPacket packet = new DatagramPacket(outBuf, clientAddr);
        int trafficBytes = packet.content().readableBytes();
        if (socksUpstreamResponse) {
            ((SocksUdpUpstream) sc.getUpstream()).recordUdpTraffic(relay, trafficBytes);
        }
        SocksUserTraffic.recordRead(relay, sc, trafficBytes, 1L);
        Sockets.UdpWriteResult result = Sockets.writeUdp(relay, packet, "socks.udp",
                udpMetricTags(relay, config, true, sc != null ? sc.getUpstream() : null));
        if (result != Sockets.UdpWriteResult.ACCEPTED) {
            log.warn("socks5[{}] UDP relay drop response from {} to {} result={}",
                    config.getListenPort(), sender, clientAddr, result);
        }
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

    private static void registerRelayAddressIfMissing(Channel relay, InetSocketAddress relayAddress, SocksContext context) {
        ConcurrentMap<InetSocketAddress, SocksContext> current = relay.attr(ATTR_CTX_MAP).get();
        if (current != null && current.get(relayAddress) == context) {
            return;
        }
        ctxMap(relay).put(relayAddress, context);
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

    private static MemoryCache<UnresolvedEndpoint, UdpManager.HeaderTemplate> routeHeaderCache(Channel relay) {
        MemoryCache<UnresolvedEndpoint, UdpManager.HeaderTemplate> cache = relay.attr(ATTR_ROUTE_HEADER_CACHE).get();
        if (cache != null) {
            return cache;
        }
        MemoryCache<UnresolvedEndpoint, UdpManager.HeaderTemplate> newCache = new MemoryCache<>(b -> b.maximumSize(256));
        MemoryCache<UnresolvedEndpoint, UdpManager.HeaderTemplate> oldCache = relay.attr(ATTR_ROUTE_HEADER_CACHE).setIfAbsent(newCache);
        return oldCache != null ? oldCache : newCache;
    }

    private static UdpManager.HeaderTemplate routeHeaderTemplate(Channel relay, UnresolvedEndpoint destination) {
        MemoryCache<UnresolvedEndpoint, UdpManager.HeaderTemplate> cache = routeHeaderCache(relay);
        UdpManager.HeaderTemplate template = cache.get(destination);
        if (template != null) {
            return template;
        }
        UdpManager.HeaderTemplate newTemplate = UdpManager.socks5HeaderTemplate(destination);
        UdpManager.HeaderTemplate oldTemplate = cache.putIfAbsent(destination, newTemplate);
        return oldTemplate != null ? oldTemplate : newTemplate;
    }

    private static ConcurrentMap<UnresolvedEndpoint, InetSocketAddress> directTargetMap(Channel relay) {
        ConcurrentMap<UnresolvedEndpoint, InetSocketAddress> map = relay.attr(ATTR_DIRECT_TARGET_MAP).get();
        if (map != null) {
            return map;
        }
        ConcurrentMap<UnresolvedEndpoint, InetSocketAddress> newMap = MemoryCache.<UnresolvedEndpoint, InetSocketAddress>rootBuilder().maximumSize(256).build().asMap();
        ConcurrentMap<UnresolvedEndpoint, InetSocketAddress> oldMap = relay.attr(ATTR_DIRECT_TARGET_MAP).setIfAbsent(newMap);
        return oldMap != null ? oldMap : newMap;
    }

    private static boolean isRouteReady(Channel relay, SocksContext context) {
        Upstream upstream = context.getUpstream();
        if (!(upstream instanceof SocksUdpUpstream)) {
            return resolveUpstreamTarget(relay, upstream) != null;
        }
        return ((SocksUdpUpstream) upstream).getUdpRelayAddress(relay) != null;
    }

    private static InetSocketAddress resolveUpstreamTarget(Channel relay, Upstream upstream) {
        if (upstream instanceof SocksUdpUpstream) {
            return ((SocksUdpUpstream) upstream).getUdpRelayAddress(relay);
        }
        UnresolvedEndpoint destination = upstream.getDestination();
        InetSocketAddress target = destination.socketAddress();
        if (!target.isUnresolved()) {
            return target;
        }
        ConcurrentMap<UnresolvedEndpoint, InetSocketAddress> map = relay.attr(ATTR_DIRECT_TARGET_MAP).get();
        return map == null ? null : map.get(destination);
    }

    private CompletableFuture<InetSocketAddress> resolveUpstreamTargetAsync(Channel relay, SocksContext context) {
        Upstream upstream = context.getUpstream();
        if (upstream instanceof SocksUdpUpstream) {
            return CompletableFuture.completedFuture(((SocksUdpUpstream) upstream).getUdpRelayAddress(relay));
        }

        UnresolvedEndpoint destination = upstream.getDestination();
        InetSocketAddress target = destination.socketAddress();
        if (!target.isUnresolved()) {
            return CompletableFuture.completedFuture(target);
        }

        ConcurrentMap<UnresolvedEndpoint, InetSocketAddress> map = directTargetMap(relay);
        InetSocketAddress cached = map.get(destination);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }

        SocksConfig config = Sockets.getAttr(relay, SocksContext.SOCKS_SVR).config;
        return Sockets.resolveUdpEndpointAsync(target, config).thenApply(resolved -> {
            InetSocketAddress old = map.putIfAbsent(destination, resolved);
            return old != null ? old : resolved;
        });
    }

    private static void runOnRelayLoop(Channel relay, Runnable task) {
        if (relay.eventLoop().inEventLoop()) {
            task.run();
        } else {
            relay.eventLoop().execute(task);
        }
    }

    public static void onUpstreamSessionInvalidated(Channel relay, InetSocketAddress relayAddress, Upstream upstream) {
        if (relay == null || upstream == null) {
            return;
        }
        InetSocketAddress[] relayAddresses = relayAddress != null
                ? new InetSocketAddress[]{relayAddress}
                : upstream instanceof SocksUdpUpstream
                ? ((SocksUdpUpstream) upstream).snapshotUdpRelayAddresses(relay)
                : null;
        onUpstreamSessionInvalidated(relay, relayAddresses, upstream);
    }

    public static void onUpstreamSessionInvalidated(Channel relay, InetSocketAddress[] relayAddresses, Upstream upstream) {
        if (relay == null || upstream == null) {
            return;
        }
        runOnRelayLoop(relay, () -> cleanupInvalidatedRoute(relay, relayAddresses, upstream));
    }

    public static void onUpstreamRelayRemoved(Channel relay, InetSocketAddress relayAddress, Upstream upstream) {
        if (relay == null || relayAddress == null || upstream == null) {
            return;
        }
        runOnRelayLoop(relay, () -> {
            ConcurrentMap<InetSocketAddress, SocksContext> current = relay.attr(ATTR_CTX_MAP).get();
            ConcurrentMap<InetSocketAddress, SocksContext> udp2rawCurrent = relay.attr(Udp2rawHandler.ATTR_CTX_MAP).get();
            int removed = removeRelayAddress(current, relayAddress, upstream)
                    + removeRelayAddress(udp2rawCurrent, relayAddress, upstream);
            if (removed > 0) {
                DiagnosticMetrics.record("socks.udp.session.cleanup.count", 1D,
                        "action=hop-remove");
            }
            recordCacheSizes(relay, current, relay.attr(ATTR_ROUTE_MAP).get(), "hop-remove");
        });
    }

    public static void onUpstreamRelayAdded(Channel relay, InetSocketAddress relayAddress, Upstream upstream) {
        if (relay == null || relayAddress == null || upstream == null) {
            return;
        }
        runOnRelayLoop(relay, () -> {
            SocksContext context = findRouteContext(relay, upstream);
            if (context != null) {
                ctxMap(relay).put(relayAddress, context);
                recordCacheSizes(relay, relay.attr(ATTR_CTX_MAP).get(), relay.attr(ATTR_ROUTE_MAP).get(), "hop-add");
            }
            SocksContext udp2rawContext = findRouteContext(relay, Udp2rawHandler.ATTR_ROUTE_MAP, upstream);
            if (udp2rawContext != null) {
                ConcurrentMap<InetSocketAddress, SocksContext> current = relay.attr(Udp2rawHandler.ATTR_CTX_MAP).get();
                if (current != null) {
                    current.put(relayAddress, udp2rawContext);
                }
            }
        });
    }

    private void beginRouteInit(Channel relay, SocksProxyServer server, InetSocketAddress clientOriginAddr,
            UnresolvedEndpoint dstEp, RouteInitState initState,
            ConcurrentMap<InetSocketAddress, SocksContext> ctxMap,
            ConcurrentMap<UnresolvedEndpoint, SocksContext> routeMap,
            ConcurrentMap<UnresolvedEndpoint, RouteInitState> routeInitMap) {
        try {
            SocksContext context = initState.context;
            if (context == null) {
                context = SocksContext.getCtx(clientOriginAddr, dstEp);
                SocksUserTraffic.attachFromChannel(context, relay);
                final SocksContext newContext = context;
                Tasks.runAsync(() -> {
                    server.publishEvent(server.onUdpRoute, newContext);
                    return newContext;
                }).whenComplete((routeContext, routeError) -> runOnRelayLoop(relay, () -> {
                    if (routeError != null) {
                        onRouteInitFailure(relay, dstEp, initState, routeMap, routeInitMap, routeError);
                        return;
                    }
                    initState.context = routeContext;
                    continueRouteInit(relay, dstEp, routeContext, initState, ctxMap, routeMap, routeInitMap);
                }));
                return;
            }
            continueRouteInit(relay, dstEp, context, initState, ctxMap, routeMap, routeInitMap);
        } catch (Throwable e) {
            runOnRelayLoop(relay, () -> onRouteInitFailure(relay, dstEp, initState, routeMap, routeInitMap, e));
        }
    }

    private void continueRouteInit(Channel relay, UnresolvedEndpoint dstEp, SocksContext context,
            RouteInitState initState, ConcurrentMap<InetSocketAddress, SocksContext> ctxMap,
            ConcurrentMap<UnresolvedEndpoint, SocksContext> routeMap,
            ConcurrentMap<UnresolvedEndpoint, RouteInitState> routeInitMap) {
        try {
            Upstream upstream = context.getUpstream();
            if (upstream == null) {
                throw new IllegalStateException("UDP route upstream is null for " + dstEp);
            }
            CompletableFuture<Void> readyFuture = upstream.initChannelAsync(relay);
            readyFuture.whenComplete((v, error) -> runOnRelayLoop(relay, () -> {
                if (error != null) {
                    onRouteInitFailure(relay, dstEp, initState, routeMap, routeInitMap, error);
                    return;
                }
                resolveUpstreamTargetAsync(relay, context).whenComplete((upDstAddr, resolveError) ->
                        runOnRelayLoop(relay, () -> {
                            if (resolveError != null) {
                                onRouteInitFailure(relay, dstEp, initState, routeMap, routeInitMap, resolveError);
                                return;
                            }
                            onRouteInitSuccess(relay, dstEp, upDstAddr, context, initState, ctxMap, routeMap, routeInitMap);
                        }));
            }));
        } catch (Throwable e) {
            onRouteInitFailure(relay, dstEp, initState, routeMap, routeInitMap, e);
        }
    }

    private void onRouteInitSuccess(Channel relay, UnresolvedEndpoint dstEp, InetSocketAddress upDstAddr,
            SocksContext context, RouteInitState initState,
            ConcurrentMap<InetSocketAddress, SocksContext> ctxMap,
            ConcurrentMap<UnresolvedEndpoint, SocksContext> routeMap,
            ConcurrentMap<UnresolvedEndpoint, RouteInitState> routeInitMap) {
        if (!routeInitMap.remove(dstEp, initState)) {
            releasePending(initState);
            return;
        }

        if (upDstAddr == null) {
            releasePending(initState);
            log.warn("socks5[{}] UDP route not ready after async init for {}", Sockets.getAttr(relay, SocksContext.SOCKS_SVR).config.getListenPort(), dstEp);
            return;
        }

        Upstream upstream = context.getUpstream();
        if (upstream instanceof SocksUdpUpstream) {
            InetSocketAddress[] relayAddresses = ((SocksUdpUpstream) upstream).snapshotUdpRelayAddresses(relay);
            if (relayAddresses.length == 0) {
                ctxMap.put(upDstAddr, context);
            } else {
                for (InetSocketAddress relayAddress : relayAddresses) {
                    ctxMap.put(relayAddress, context);
                }
            }
        } else {
            ctxMap.put(upDstAddr, context);
        }
        routeMap.put(dstEp, context);
        relay.attr(ATTR_LAST_ROUTE).set(new LastRoute(routeHeaderTemplate(relay, dstEp), context));
        recordCacheSizes(relay, ctxMap, routeMap, "route-ready");
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
            recordDrop("pending-route-overflow", sender, clientOriginAddr, config, bytes);
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

    private static void releasePending(RouteInitState initState) {
        PendingPacket packet;
        while ((packet = initState.pendingPackets.pollFirst()) != null) {
            Bytes.release(packet.content);
        }
        initState.pendingBytes = 0;
    }

    static void clearRelayState(Channel relay) {
        ConcurrentMap<InetSocketAddress, SocksContext> ctxMap = relay.attr(ATTR_CTX_MAP).get();
        if (ctxMap != null) {
            ctxMap.clear();
        }
        ConcurrentMap<UnresolvedEndpoint, SocksContext> routeMap = relay.attr(ATTR_ROUTE_MAP).get();
        if (routeMap != null) {
            routeMap.clear();
        }
        ConcurrentMap<UnresolvedEndpoint, RouteInitState> routeInitMap = relay.attr(ATTR_ROUTE_INIT_MAP).get();
        if (routeInitMap != null) {
            for (RouteInitState state : routeInitMap.values()) {
                releasePending(state);
            }
            routeInitMap.clear();
        }
        ConcurrentMap<UnresolvedEndpoint, InetSocketAddress> directTargetMap = relay.attr(ATTR_DIRECT_TARGET_MAP).get();
        if (directTargetMap != null) {
            directTargetMap.clear();
        }
        relay.attr(ATTR_LAST_ROUTE).set(null);
    }

    private void writeClientPacket(Channel relay, ByteBuf inBuf, InetSocketAddress sender,
            InetSocketAddress clientOriginAddr, UnresolvedEndpoint dstEp,
            SocksContext context, boolean retained) {
        Upstream upstream = context.getUpstream();
        SocksUdpUpstream socksUpstream = upstream instanceof SocksUdpUpstream ? (SocksUdpUpstream) upstream : null;
        int trafficBytes = 0;
        InetSocketAddress upDstAddr;
        if (socksUpstream != null) {
            inBuf.resetReaderIndex();
            trafficBytes = inBuf.readableBytes();
            upDstAddr = socksUpstream.selectUdpRelayAddressAndRecord(relay, trafficBytes);
        } else {
            upDstAddr = resolveUpstreamTarget(relay, upstream);
        }
        if (upDstAddr == null) {
            if (retained) {
                Bytes.release(inBuf);
            }
            recordDrop("relay-not-ready", sender, clientOriginAddr,
                    Sockets.getAttr(relay, SocksContext.SOCKS_SVR).config, inBuf.readableBytes());
            log.warn("socks5[{}] UDP relay not ready for {}, drop packet from {}",
                    Sockets.getAttr(relay, SocksContext.SOCKS_SVR).config.getListenPort(), dstEp, sender);
            return;
        }

        if (socksUpstream != null) {
            registerRelayAddressIfMissing(relay, upDstAddr, context);
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
        DatagramPacket packet = new DatagramPacket(inBuf, upDstAddr);
        if (trafficBytes == 0) {
            trafficBytes = packet.content().readableBytes();
        }
        SocksUserTraffic.recordWrite(relay, context, trafficBytes, 1L);
        Sockets.UdpWriteResult result = Sockets.writeUdp(relay, packet, "socks.udp",
                udpMetricTags(relay, config, false, upstream));
        if (result != Sockets.UdpWriteResult.ACCEPTED) {
            log.warn("socks5[{}] UDP relay drop packet from {} to {}[{}] result={}",
                    config.getListenPort(), sender, upDstAddr, dstEp, result);
        }
    }

    private static void cleanupInvalidatedRoute(Channel relay, InetSocketAddress[] relayAddresses, Upstream upstream) {
        ConcurrentMap<InetSocketAddress, SocksContext> ctxMap = relay.attr(ATTR_CTX_MAP).get();
        ConcurrentMap<InetSocketAddress, SocksContext> udp2rawCtxMap = relay.attr(Udp2rawHandler.ATTR_CTX_MAP).get();
        ConcurrentMap<UnresolvedEndpoint, SocksContext> routeMap = relay.attr(ATTR_ROUTE_MAP).get();
        ConcurrentMap<UnresolvedEndpoint, SocksContext> udp2rawRouteMap = relay.attr(Udp2rawHandler.ATTR_ROUTE_MAP).get();
        ConcurrentMap<UnresolvedEndpoint, RouteInitState> routeInitMap = relay.attr(ATTR_ROUTE_INIT_MAP).get();
        int removedCtx = 0, removedRoute = 0, removedPending = 0;

        removedCtx += cleanupCtxMap(ctxMap, relayAddresses, upstream);
        removedCtx += cleanupCtxMap(udp2rawCtxMap, relayAddresses, upstream);

        removedRoute += cleanupRouteMap(routeMap, upstream);
        removedRoute += cleanupRouteMap(udp2rawRouteMap, upstream);

        if (routeInitMap != null) {
            for (Map.Entry<UnresolvedEndpoint, RouteInitState> entry : routeInitMap.entrySet()) {
                RouteInitState state = entry.getValue();
                SocksContext ctx = state != null ? state.context : null;
                if (ctx != null && ctx.getUpstream() == upstream && routeInitMap.remove(entry.getKey(), state)) {
                    releasePending(state);
                    removedPending++;
                }
            }
        }

        LastRoute lastRoute = relay.attr(ATTR_LAST_ROUTE).get();
        if (lastRoute != null && lastRoute.context != null && lastRoute.context.getUpstream() == upstream) {
            relay.attr(ATTR_LAST_ROUTE).set(null);
        }

        if (removedCtx > 0 || removedRoute > 0 || removedPending > 0) {
            DiagnosticMetrics.record("socks.udp.session.cleanup.count", 1D,
                    "action=session-cleanup");
        }
        recordCacheSizes(relay, ctxMap, routeMap, "session-cleanup");
    }

    private static int removeRelayAddress(ConcurrentMap<InetSocketAddress, SocksContext> ctxMap,
            InetSocketAddress relayAddress, Upstream upstream) {
        if (ctxMap == null || relayAddress == null) {
            return 0;
        }
        SocksContext ctx = ctxMap.get(relayAddress);
        if (ctx != null && ctx.getUpstream() == upstream && ctxMap.remove(relayAddress, ctx)) {
            return 1;
        }
        return 0;
    }

    private static int cleanupCtxMap(ConcurrentMap<InetSocketAddress, SocksContext> ctxMap,
            InetSocketAddress[] relayAddresses, Upstream upstream) {
        int removed = 0;
        if (ctxMap == null) {
            return 0;
        }
        if (relayAddresses != null && relayAddresses.length > 0) {
            for (InetSocketAddress relayAddress : relayAddresses) {
                removed += removeRelayAddress(ctxMap, relayAddress, upstream);
            }
            return removed;
        }
        if (upstream instanceof SocksUdpUpstream) {
            return 0;
        }
        for (Map.Entry<InetSocketAddress, SocksContext> entry : ctxMap.entrySet()) {
            SocksContext ctx = entry.getValue();
            if (ctx != null && ctx.getUpstream() == upstream && ctxMap.remove(entry.getKey(), ctx)) {
                removed++;
            }
        }
        return removed;
    }

    private static int cleanupRouteMap(ConcurrentMap<UnresolvedEndpoint, SocksContext> routeMap, Upstream upstream) {
        int removed = 0;
        if (routeMap == null) {
            return 0;
        }
        for (Map.Entry<UnresolvedEndpoint, SocksContext> entry : routeMap.entrySet()) {
            SocksContext ctx = entry.getValue();
            if (ctx != null && ctx.getUpstream() == upstream && routeMap.remove(entry.getKey(), ctx)) {
                removed++;
            }
        }
        return removed;
    }

    private static SocksContext findRouteContext(Channel relay, Upstream upstream) {
        LastRoute lastRoute = relay.attr(ATTR_LAST_ROUTE).get();
        if (lastRoute != null && lastRoute.context != null && lastRoute.context.getUpstream() == upstream) {
            return lastRoute.context;
        }
        return findRouteContext(relay, ATTR_ROUTE_MAP, upstream);
    }

    private static SocksContext findRouteContext(Channel relay,
            AttributeKey<ConcurrentMap<UnresolvedEndpoint, SocksContext>> routeMapKey, Upstream upstream) {
        ConcurrentMap<UnresolvedEndpoint, SocksContext> routeMap = relay.attr(routeMapKey).get();
        if (routeMap == null) {
            return null;
        }
        for (SocksContext context : routeMap.values()) {
            if (context != null && context.getUpstream() == upstream) {
                return context;
            }
        }
        return null;
    }

    private static void recordCacheSizes(Channel relay, ConcurrentMap<InetSocketAddress, SocksContext> ctxMap,
            ConcurrentMap<UnresolvedEndpoint, SocksContext> routeMap, String action) {
        if (ctxMap != null) {
            DiagnosticMetrics.record("socks.udp.ctx.cache.size", ctxMap.size(), "action=" + action);
        }
        if (routeMap != null) {
            DiagnosticMetrics.record("socks.udp.route.cache.size", routeMap.size(), "action=" + action);
        }
    }

    private static void recordDrop(String reason, InetSocketAddress sender, InetSocketAddress clientOriginAddr,
            SocksConfig config, int bytes) {
        DiagnosticMetrics.record("socks.udp.drop.count", 1D,
                "reason=" + reason + ",path=client-ingress");
    }

    private static String udpMetricTags(Channel relay, SocksConfig config, boolean toClient, Upstream upstream) {
        UdpMetricTagCache cache = relay.attr(ATTR_UDP_METRIC_TAG_CACHE).get();
        if (cache == null) {
            UdpMetricTagCache newCache = new UdpMetricTagCache();
            UdpMetricTagCache oldCache = relay.attr(ATTR_UDP_METRIC_TAG_CACHE).setIfAbsent(newCache);
            cache = oldCache != null ? oldCache : newCache;
        }
        return cache.tags(toClient, upstream);
    }

}
