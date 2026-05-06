package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.EventExecutor;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Tasks;
import org.rx.core.cache.MemoryCache;
import org.rx.diagnostic.DiagnosticMetrics;
import org.rx.io.Bytes;
import org.rx.net.Sockets;
import org.rx.net.socks.upstream.SocksUdpUpstream;
import org.rx.net.socks.upstream.Udp2rawUpstream;
import org.rx.net.socks.upstream.UdpClientUpstream;
import org.rx.net.socks.upstream.Upstream;
import org.rx.net.support.EndpointTracer;
import org.rx.net.support.UnresolvedEndpoint;

import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@ChannelHandler.Sharable
public class SSUdpProxyHandler extends SimpleChannelInboundHandler<DatagramPacket> {
    public static final AttributeKey<ConcurrentMap<RouteKey, SocksContext>> ATTR_ROUTE_MAP =
            AttributeKey.valueOf("ssUdpRouteMap");
    static final AttributeKey<ConcurrentMap<RouteKey, RouteInitState>> ATTR_ROUTE_INIT_MAP =
            AttributeKey.valueOf("ssUdpRouteInitMap");
    static final AttributeKey<LastRoute> ATTR_LAST_ROUTE = AttributeKey.valueOf("ssUdpLastRoute");
    static final AttributeKey<OutboundBinding> ATTR_OUTBOUND_BINDING =
            AttributeKey.valueOf("ssUdpOutboundBinding");
    static final AttributeKey<ConcurrentMap<UnresolvedEndpoint, SocksContext>> ATTR_OUTBOUND_ROUTE_MAP =
            AttributeKey.valueOf("ssUdpOutboundRouteMap");
    static final AttributeKey<MemoryCache<UnresolvedEndpoint, UdpManager.HeaderTemplate>> ATTR_SOCKS5_HEADER_CACHE =
            AttributeKey.valueOf("ssUdpSocks5HeaderCache");
    static final AttributeKey<MemoryCache<InetSocketAddress, UdpManager.HeaderTemplate>> ATTR_ADDRESS_HEADER_CACHE =
            AttributeKey.valueOf("ssUdpAddressHeaderCache");
    static final AttributeKey<ConcurrentMap<InetSocketAddress, AtomicInteger>> ATTR_UDP_PENDING_WRITE_BYTES_BY_SOURCE =
            AttributeKey.valueOf("ssUdpPendingWriteBytesBySource");
    static final String REDUNDANT_DECODER_NAME = "SS_UDP_REDUNDANT_DECODER";
    static final String COMPRESS_DECODER_NAME = "SS_UDP_COMPRESS_DECODER";
    static final int MAX_PENDING_ROUTE_PACKETS = 32;
    static final int MAX_PENDING_ROUTE_BYTES = 256 * 1024;
    static final int DEFAULT_OUTBOUND_IDLE_SECONDS = 120;
    private static final String UDP_TAG_FRONTEND_OUTBOUND = "path=frontend,flow=to-upstream";
    private static final String UDP_TAG_FRONTEND_OUTBOUND_SOCKS = UDP_TAG_FRONTEND_OUTBOUND + ",mode=socks";
    private static final String UDP_TAG_FRONTEND_OUTBOUND_UDP2RAW = UDP_TAG_FRONTEND_OUTBOUND + ",mode=udp2raw";
    private static final String UDP_TAG_FRONTEND_OUTBOUND_UDP_CLIENT = UDP_TAG_FRONTEND_OUTBOUND + ",mode=udp-client";
    private static final String UDP_TAG_FRONTEND_OUTBOUND_DIRECT = UDP_TAG_FRONTEND_OUTBOUND + ",mode=direct";
    private static final String UDP_TAG_BACKEND_INBOUND = "path=backend,flow=to-client";
    private static final String UDP_TAG_BACKEND_INBOUND_SOCKS = UDP_TAG_BACKEND_INBOUND + ",mode=socks";
    private static final String UDP_TAG_BACKEND_INBOUND_UDP2RAW = UDP_TAG_BACKEND_INBOUND + ",mode=udp2raw";
    private static final String UDP_TAG_BACKEND_INBOUND_UDP_CLIENT = UDP_TAG_BACKEND_INBOUND + ",mode=udp-client";
    private static final String UDP_TAG_BACKEND_INBOUND_DIRECT = UDP_TAG_BACKEND_INBOUND + ",mode=direct";
    static final ConcurrentHashMap<OutboundPoolKey, ChannelFuture> OUTBOUND_POOL = new ConcurrentHashMap<>();
    static final ConcurrentHashMap<OutboundSourceKey, AtomicInteger> OUTBOUND_POOL_SOURCE_COUNTS = new ConcurrentHashMap<>();

    static final class RouteKey {
        final InetSocketAddress source;
        final UnresolvedEndpoint destination;
        final int hash;

        RouteKey(InetSocketAddress source, UnresolvedEndpoint destination) {
            this.source = source;
            this.destination = destination;
            this.hash = 31 * Objects.hashCode(source) + Objects.hashCode(destination);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof RouteKey)) {
                return false;
            }
            RouteKey that = (RouteKey) o;
            return Objects.equals(source, that.source) && Objects.equals(destination, that.destination);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    static final class LastRoute {
        final InetSocketAddress source;
        final UnresolvedEndpoint destination;
        final SocksContext context;

        LastRoute(InetSocketAddress source, UnresolvedEndpoint destination, SocksContext context) {
            this.source = source;
            this.destination = destination;
            this.context = context;
        }

        boolean matches(InetSocketAddress source, UnresolvedEndpoint destination) {
            return Objects.equals(this.source, source) && Objects.equals(this.destination, destination);
        }
    }

    static final class PendingPacket {
        final ByteBuf content;

        PendingPacket(ByteBuf content) {
            this.content = content;
        }
    }

    static final class RouteInitState {
        final ArrayDeque<PendingPacket> pendingPackets = new ArrayDeque<>();
        int pendingBytes;
    }

    static final class OutboundPoolKey {
        final ChannelId inboundId;
        final InetSocketAddress source;
        final OutboundSourceKey sourceKey;
        final Class<?> upstreamType;
        final Object affinity;
        final int hash;
        final AtomicBoolean sourceReleased = new AtomicBoolean();

        OutboundPoolKey(Channel inbound, InetSocketAddress source, Upstream upstream) {
            this.inboundId = inbound.id();
            this.source = source;
            this.sourceKey = new OutboundSourceKey(inbound, source);
            this.upstreamType = upstream.getClass();
            this.affinity = upstream instanceof SocksUdpUpstream ? ((SocksUdpUpstream) upstream).poolKey()
                    : upstream instanceof Udp2rawUpstream ? ((Udp2rawUpstream) upstream).tunnelAffinity()
                    : upstream instanceof UdpClientUpstream ? ((UdpClientUpstream) upstream).clientAffinity()
                    : upstream.getConfig();
            this.hash = (((inboundId.hashCode() * 31) + Objects.hashCode(source)) * 31 + upstreamType.hashCode()) * 31 + Objects.hashCode(affinity);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof OutboundPoolKey)) {
                return false;
            }
            OutboundPoolKey that = (OutboundPoolKey) o;
            return Objects.equals(inboundId, that.inboundId)
                    && Objects.equals(source, that.source)
                    && Objects.equals(upstreamType, that.upstreamType)
                    && Objects.equals(affinity, that.affinity);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    static final class OutboundSourceKey {
        final ChannelId inboundId;
        final InetSocketAddress source;
        final int hash;

        OutboundSourceKey(Channel inbound, InetSocketAddress source) {
            this.inboundId = inbound.id();
            this.source = source;
            this.hash = 31 * inboundId.hashCode() + Objects.hashCode(source);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof OutboundSourceKey)) {
                return false;
            }
            OutboundSourceKey that = (OutboundSourceKey) o;
            return Objects.equals(inboundId, that.inboundId)
                    && Objects.equals(source, that.source);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    static final class OutboundBinding {
        final Channel inbound;
        final InetSocketAddress source;
        final Upstream representativeUpstream;

        OutboundBinding(Channel inbound, InetSocketAddress source, Upstream representativeUpstream) {
            this.inbound = inbound;
            this.source = source;
            this.representativeUpstream = representativeUpstream;
        }
    }

    private static InetSocketAddress relayAddress(Upstream upstream, Channel channel) {
        if (upstream instanceof SocksUdpUpstream) {
            return ((SocksUdpUpstream) upstream).getUdpRelayAddress(channel);
        }
        if (upstream instanceof Udp2rawUpstream) {
            return ((Udp2rawUpstream) upstream).getUdpEntryAddress(channel);
        }
        if (upstream instanceof UdpClientUpstream) {
            return ((UdpClientUpstream) upstream).getUdpClientAddress(channel);
        }
        return null;
    }

    private static ShadowsocksConfig ssConfig(SocksContext sc) {
        if (sc == null || sc.inbound == null) {
            return null;
        }
        ShadowsocksServer server = Sockets.getAttr(sc.inbound, ShadowsocksConfig.SVR, false);
        return server != null ? server.config : null;
    }

    private static void runOnExecutor(EventExecutor executor, Runnable task) {
        if (executor.inEventLoop()) {
            task.run();
        } else {
            executor.execute(task);
        }
    }

    private static void ensureRelayResponseDecoder(ChannelPipeline pipeline, Upstream upstream) {
        if (!(upstream instanceof SocksUdpUpstream)) {
            return;
        }
        // SS -> socks a 不需要做冗余/压缩发送，但 socks a -> 本地 relay 的回包可能带 RDNT / UCMP 头；
        // 这里只补回程 decoder，负责去重、解压、剥头，不安装 encoder 避免把本地跳也放大。
        if (pipeline.get(REDUNDANT_DECODER_NAME) == null && pipeline.get(UdpRedundantDecoder.class) == null) {
            pipeline.addLast(REDUNDANT_DECODER_NAME, new UdpRedundantDecoder());
        }
        if (pipeline.get(COMPRESS_DECODER_NAME) == null && pipeline.get(UdpCompressDecoder.class) == null) {
            pipeline.addLast(COMPRESS_DECODER_NAME, new UdpCompressDecoder());
        }
    }

    static int resolveOutboundReadIdleSeconds(ShadowsocksConfig config) {
        return config.getUdpReadTimeoutSeconds() > 0 || config.getUdpWriteTimeoutSeconds() > 0
                ? config.getUdpReadTimeoutSeconds()
                : DEFAULT_OUTBOUND_IDLE_SECONDS;
    }

    static int resolveOutboundWriteIdleSeconds(ShadowsocksConfig config) {
        return config.getUdpReadTimeoutSeconds() > 0 || config.getUdpWriteTimeoutSeconds() > 0
                ? config.getUdpWriteTimeoutSeconds()
                : 0;
    }

    private static void installOutboundIdleHandler(ChannelPipeline pipeline, ShadowsocksConfig config) {
        if (pipeline.get(ProxyChannelIdleHandler.class) != null) {
            return;
        }
        int readIdleSeconds = resolveOutboundReadIdleSeconds(config);
        int writeIdleSeconds = resolveOutboundWriteIdleSeconds(config);
        if (readIdleSeconds > 0 || writeIdleSeconds > 0) {
            pipeline.addLast(new ProxyChannelIdleHandler(readIdleSeconds, writeIdleSeconds));
        }
    }

    private static DatagramPacket buildOutboundPacket(SocksContext sc, Channel outbound,
            InetSocketAddress srcEp, UnresolvedEndpoint dstEp, ByteBuf payload) {
        Upstream upstream = sc.getUpstream();
        if (upstream instanceof Udp2rawUpstream) {
            return ((Udp2rawUpstream) upstream).buildRequestPacket(outbound, payload, srcEp, dstEp);
        }
        if (upstream instanceof UdpClientUpstream) {
            UdpManager.HeaderTemplate headerTemplate = socks5HeaderTemplate(sc.inbound, dstEp);
            return new DatagramPacket(UdpManager.socks5Encode(payload, headerTemplate),
                    ((UdpClientUpstream) upstream).getUdpClientAddress(outbound));
        }
        if (upstream instanceof SocksUdpUpstream) {
            UdpManager.HeaderTemplate headerTemplate = socks5HeaderTemplate(sc.inbound, dstEp);
            int trafficBytes = payload.readableBytes() + headerTemplate.length();
            InetSocketAddress udpRelayAddr = ((SocksUdpUpstream) upstream).selectUdpRelayAddressAndRecord(outbound, trafficBytes);
            if (udpRelayAddr == null) {
                return null;
            }
            return new DatagramPacket(UdpManager.socks5Encode(payload, headerTemplate), udpRelayAddr);
        }
        return new DatagramPacket(payload, upstream.getDestination().socketAddress());
    }

    private static boolean isRouteActive(SocksContext context) {
        return context != null && context.outbound != null && context.outbound.channel().isActive();
    }

    private static void clearLastRoute(Channel inbound, RouteKey routeKey, SocksContext context) {
        LastRoute lastRoute = inbound.attr(ATTR_LAST_ROUTE).get();
        if (lastRoute != null && lastRoute.context == context && lastRoute.matches(routeKey.source, routeKey.destination)) {
            inbound.attr(ATTR_LAST_ROUTE).set(null);
        }
    }

    private static SocksContext lookupRoute(Channel inbound, ConcurrentMap<RouteKey, SocksContext> routeMap,
            InetSocketAddress srcEp, UnresolvedEndpoint dstEp) {
        LastRoute lastRoute = inbound.attr(ATTR_LAST_ROUTE).get();
        if (lastRoute != null && lastRoute.matches(srcEp, dstEp)) {
            if (isRouteActive(lastRoute.context)) {
                return lastRoute.context;
            }
            inbound.attr(ATTR_LAST_ROUTE).set(null);
        }

        RouteKey routeKey = new RouteKey(srcEp, dstEp);
        SocksContext context = routeMap.get(routeKey);
        if (context != null) {
            if (!isRouteActive(context)) {
                routeMap.remove(routeKey, context);
                return null;
            }
            inbound.attr(ATTR_LAST_ROUTE).set(new LastRoute(srcEp, dstEp, context));
        }
        return context;
    }

    private static void runOnOutboundLoop(Channel outbound, Runnable task) {
        if (outbound.eventLoop().inEventLoop()) {
            task.run();
        } else {
            outbound.eventLoop().execute(task);
        }
    }

    private static void writePacketNow(SocksContext sc, Channel outbound, UnresolvedEndpoint dstEp,
            ByteBuf payload, InetSocketAddress srcEp, boolean debug) {
        if (!outbound.isActive()) {
            int bytes = readableBytesOf(payload);
            Bytes.release(payload);
            recordUdpDrop("inactive", srcEp, dstEp, null, bytes);
            log.warn("SS UDP relay outbound closed for {}, drop packet from {}", dstEp, srcEp);
            return;
        }

        Upstream upstream = sc.getUpstream();
        if (upstream instanceof Udp2rawUpstream) {
            Udp2rawUpstream udp2rawUpstream = (Udp2rawUpstream) upstream;
            InetSocketAddress udpRelayAddr = udp2rawUpstream.getUdpEntryAddress(outbound);
            boolean accepted;
            try {
                accepted = udp2rawUpstream.writeRequest(outbound, payload, srcEp, dstEp, true);
            } catch (Throwable ex) {
                log.warn("SS UDP relay build udp2raw packet error for {}, drop packet from {}", dstEp, srcEp, ex);
                return;
            }
            if (!accepted) {
                log.warn("SS UDP relay drop udp2raw packet from {} for {}", srcEp, dstEp);
                return;
            }
            if (debug) {
                log.info("SS UDP2RAW OUT {} => {}[{}]", srcEp, udpRelayAddr, dstEp);
            }
            return;
        }

        DatagramPacket packet = null;
        try {
            packet = buildOutboundPacket(sc, outbound, srcEp, dstEp, payload);
        } catch (Throwable ex) {
            log.warn("SS UDP relay build packet error for {}, drop packet from {}", dstEp, srcEp, ex);
        }
        if (packet == null) {
            int bytes = readableBytesOf(payload);
            Bytes.release(payload);
            recordUdpDrop("build-failed", srcEp, dstEp, null, bytes);
            log.warn("SS UDP relay not ready for {}, drop packet from {}", dstEp, srcEp);
            return;
        }
        InetSocketAddress udpRelayAddr = relayAddress(sc.getUpstream(), outbound);
        Sockets.UdpWriteResult result = Sockets.writeUdp(outbound, packet, ssConfig(sc), "ss.udp",
                udpMetricTags("frontend", "outbound", sc.getUpstream()));
        if (result != Sockets.UdpWriteResult.ACCEPTED) {
            log.warn("SS UDP relay drop packet from {} for {} result={}", srcEp, dstEp, result);
            return;
        }
        if (debug) {
            log.info("SS UDP OUT {} => {}[{}]", srcEp, udpRelayAddr != null ? udpRelayAddr : upstream.getDestination(), dstEp);
        }
    }

    private static void writeWhenReady(SocksContext sc, UnresolvedEndpoint dstEp, ByteBuf payload,
            InetSocketAddress srcEp, boolean debug) {
        Channel outbound = sc.outbound.channel();
        EndpointTracer.UDP.link(srcEp, outbound);
        Upstream upstream = sc.getUpstream();
        if (!outbound.isActive()) {
            int bytes = readableBytesOf(payload);
            Bytes.release(payload);
            recordUdpDrop("inactive", srcEp, dstEp, null, bytes);
            log.warn("SS UDP relay outbound closed for {}, drop packet from {}", dstEp, srcEp);
            return;
        }
        if (!(upstream instanceof SocksUdpUpstream) && !(upstream instanceof Udp2rawUpstream)
                && !(upstream instanceof UdpClientUpstream)
                || relayAddress(upstream, outbound) != null) {
            runOnOutboundLoop(outbound, () -> writePacketNow(sc, outbound, dstEp, payload, srcEp, debug));
            return;
        }

        upstream.initChannelAsync(outbound).whenComplete((v, error) -> runOnOutboundLoop(outbound, () -> {
            if (error != null) {
                int bytes = readableBytesOf(payload);
                Bytes.release(payload);
                recordUdpDrop("init-failed", srcEp, dstEp, null, bytes);
                log.warn("SS UDP relay init upstream error for {}, drop packet from {}", dstEp, srcEp, error);
                return;
            }
            writePacketNow(sc, outbound, dstEp, payload, srcEp, debug);
        }));
    }

    private static void writeRoutePacket(SocksContext context, InetSocketAddress srcEp, UnresolvedEndpoint dstEp,
            ByteBuf payload, boolean debug) {
        if (context.isOutboundReady()) {
            writeWhenReady(context, dstEp, payload, srcEp, debug);
            return;
        }

        ChannelFuture outboundFuture = context.outbound;
        outboundFuture.addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess()) {
                int bytes = readableBytesOf(payload);
                Bytes.release(payload);
                recordUdpDrop("bind-failed", srcEp, dstEp, null, bytes);
                return;
            }
            writeWhenReady(context, dstEp, payload, srcEp, debug);
        });
    }

    ChannelFuture openOutboundChannel(Channel inbound, ShadowsocksServer server, Upstream upstream,
            InetSocketAddress srcEp, OutboundPoolKey key) {
        ChannelFuture chf = Sockets.udpBootstrap(upstream.getConfig(), ob -> {
            ob.attr(UdpRelayAttributes.ATTR_CLIENT_ORIGIN_ADDR).set(srcEp);
            ensureRelayResponseDecoder(ob.pipeline(), upstream);
            installOutboundIdleHandler(ob.pipeline(), server.config);
            ob.pipeline().addLast(UdpBackendRelayHandler.DEFAULT);
        }).attr(ShadowsocksConfig.SVR, server).bind(0);
        chf.addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess()) {
                if (!removeOutboundPool(key, chf, "bind-fail")) {
                    releaseOutboundSource(key);
                    recordOutboundPoolClose("bind-fail");
                }
            }
        });
        chf.channel().closeFuture().addListener(f -> {
            removeOutboundPool(key, chf, "close");
        });
        inbound.closeFuture().addListener(f -> {
            if (removeOutboundPool(key, chf, "inbound-close") && chf.channel().isOpen()) {
                chf.channel().close();
            }
        });
        return chf;
    }

    ChannelFuture acquireOutboundChannel(Channel inbound, ShadowsocksServer server, RouteKey routeKey, Upstream upstream) {
        OutboundPoolKey key = new OutboundPoolKey(inbound, routeKey.source, upstream);
        ChannelFuture existing = OUTBOUND_POOL.get(key);
        if (existing != null) {
            return existing;
        }

        ShadowsocksConfig config = server.config;
        int poolSize = OUTBOUND_POOL.size();
        int maxSize = config.getUdpOutboundPoolMaxSize();
        if (maxSize > 0 && poolSize >= maxSize) {
            recordOutboundPoolOpen("full");
            recordUdpDrop("outbound-pool-full", routeKey.source, routeKey.destination, null, 0);
            return inbound.newFailedFuture(new IllegalStateException("SS UDP outbound pool full"));
        }
        int warnSize = config.getUdpOutboundPoolWarnSize();
        if (warnSize > 0 && poolSize >= warnSize) {
            DiagnosticMetrics.record("ss.udp.outbound.pool.warn.count", 1D, "result=warn");
        }
        if (!reserveOutboundSource(key, config.getUdpOutboundPoolMaxPerSource())) {
            recordOutboundPoolOpen("per-source-full");
            recordUdpDrop("outbound-pool-per-source-full", routeKey.source, routeKey.destination, null, 0);
            return inbound.newFailedFuture(new IllegalStateException("SS UDP outbound pool source full"));
        }

        try {
            AtomicBoolean created = new AtomicBoolean();
            ChannelFuture channelFuture = OUTBOUND_POOL.computeIfAbsent(key, k -> {
                created.set(true);
                return openOutboundChannel(inbound, server, upstream, routeKey.source, k);
            });
            if (created.get()) {
                recordOutboundPoolOpen("success");
            } else {
                releaseOutboundSource(key);
            }
            return channelFuture;
        } catch (Throwable e) {
            releaseOutboundSource(key);
            recordOutboundPoolOpen("error");
            throw e;
        }
    }

    private static void bindRouteContext(Channel inbound, ChannelFuture outboundFuture, SocksContext context) {
        context.inbound = inbound;
        context.outbound = outboundFuture;
    }

    private static void bindOutbound(Channel outbound, Channel inbound, InetSocketAddress srcEp, Upstream upstream) {
        OutboundBinding binding = outbound.attr(ATTR_OUTBOUND_BINDING).get();
        if (binding != null) {
            if (binding.inbound == inbound && Objects.equals(binding.source, srcEp)) {
                return;
            }
            throw new IllegalStateException("SS UDP outbound binding conflict for " + srcEp);
        }
        outbound.attr(ATTR_OUTBOUND_BINDING).set(new OutboundBinding(inbound, srcEp, upstream));
    }

    private static ConcurrentMap<UnresolvedEndpoint, SocksContext> outboundRouteMap(Channel outbound) {
        ConcurrentMap<UnresolvedEndpoint, SocksContext> routeMap = outbound.attr(ATTR_OUTBOUND_ROUTE_MAP).get();
        if (routeMap != null) {
            return routeMap;
        }
        ConcurrentMap<UnresolvedEndpoint, SocksContext> newMap = MemoryCache.<UnresolvedEndpoint, SocksContext>rootBuilder().maximumSize(2048).build().asMap();
        ConcurrentMap<UnresolvedEndpoint, SocksContext> oldMap = outbound.attr(ATTR_OUTBOUND_ROUTE_MAP).setIfAbsent(newMap);
        return oldMap != null ? oldMap : newMap;
    }

    private static void registerOutboundRoute(Channel outbound, UnresolvedEndpoint destination, SocksContext context) {
        outboundRouteMap(outbound).put(destination, context);
    }

    private static SocksContext lookupOutboundRoute(Channel outbound, UnresolvedEndpoint destination) {
        if (destination == null) {
            return null;
        }
        ConcurrentMap<UnresolvedEndpoint, SocksContext> routeMap = outbound.attr(ATTR_OUTBOUND_ROUTE_MAP).get();
        return routeMap != null ? routeMap.get(destination) : null;
    }

    private static void unregisterOutboundRoute(Channel outbound, UnresolvedEndpoint destination, SocksContext context) {
        ConcurrentMap<UnresolvedEndpoint, SocksContext> routeMap = outbound.attr(ATTR_OUTBOUND_ROUTE_MAP).get();
        if (routeMap != null) {
            routeMap.remove(destination, context);
        }
    }

    private static MemoryCache<UnresolvedEndpoint, UdpManager.HeaderTemplate> socks5HeaderCache(Channel inbound) {
        MemoryCache<UnresolvedEndpoint, UdpManager.HeaderTemplate> cache = inbound.attr(ATTR_SOCKS5_HEADER_CACHE).get();
        if (cache != null) {
            return cache;
        }
        MemoryCache<UnresolvedEndpoint, UdpManager.HeaderTemplate> newCache = new MemoryCache<>(b -> b.maximumSize(2048));
        MemoryCache<UnresolvedEndpoint, UdpManager.HeaderTemplate> oldCache = inbound.attr(ATTR_SOCKS5_HEADER_CACHE).setIfAbsent(newCache);
        return oldCache != null ? oldCache : newCache;
    }

    private static UdpManager.HeaderTemplate socks5HeaderTemplate(Channel inbound, UnresolvedEndpoint destination) {
        MemoryCache<UnresolvedEndpoint, UdpManager.HeaderTemplate> cache = socks5HeaderCache(inbound);
        UdpManager.HeaderTemplate template = cache.get(destination);
        if (template != null) {
            return template;
        }
        UdpManager.HeaderTemplate newTemplate = UdpManager.socks5HeaderTemplate(destination);
        UdpManager.HeaderTemplate oldTemplate = cache.putIfAbsent(destination, newTemplate);
        return oldTemplate != null ? oldTemplate : newTemplate;
    }

    private static MemoryCache<InetSocketAddress, UdpManager.HeaderTemplate> addressHeaderCache(Channel inbound) {
        MemoryCache<InetSocketAddress, UdpManager.HeaderTemplate> cache = inbound.attr(ATTR_ADDRESS_HEADER_CACHE).get();
        if (cache != null) {
            return cache;
        }
        MemoryCache<InetSocketAddress, UdpManager.HeaderTemplate> newCache = new MemoryCache<>(b -> b.maximumSize(2048));
        MemoryCache<InetSocketAddress, UdpManager.HeaderTemplate> oldCache = inbound.attr(ATTR_ADDRESS_HEADER_CACHE).setIfAbsent(newCache);
        return oldCache != null ? oldCache : newCache;
    }

    private static UdpManager.HeaderTemplate addressHeaderTemplate(Channel inbound, InetSocketAddress destination) {
        MemoryCache<InetSocketAddress, UdpManager.HeaderTemplate> cache = addressHeaderCache(inbound);
        UdpManager.HeaderTemplate template = cache.get(destination);
        if (template != null) {
            return template;
        }
        UdpManager.HeaderTemplate newTemplate = UdpManager.addressHeaderTemplate(destination);
        UdpManager.HeaderTemplate oldTemplate = cache.putIfAbsent(destination, newTemplate);
        return oldTemplate != null ? oldTemplate : newTemplate;
    }

    private void beginRouteInit(Channel inbound, EventExecutor routeExecutor, ShadowsocksServer server,
            RouteKey routeKey, RouteInitState initState,
            ConcurrentMap<RouteKey, SocksContext> routeMap,
            ConcurrentMap<RouteKey, RouteInitState> routeInitMap) {
        try {
            SocksContext context = SocksContext.getCtx(routeKey.source, routeKey.destination);
            Tasks.runAsync(() -> {
                server.publishEvent(server.onUdpRoute, context);
                return context;
            }).whenComplete((routeContext, routeError) -> runOnExecutor(routeExecutor, () -> {
                if (routeError != null) {
                    onRouteInitFailure(routeKey, initState, routeInitMap, routeError);
                    return;
                }

                Upstream upstream = routeContext.getUpstream();
                if (upstream == null) {
                    onRouteInitFailure(routeKey, initState, routeInitMap,
                            new IllegalStateException("SS UDP route upstream is null for " + routeKey.destination));
                    return;
                }

                try {
                    ChannelFuture outboundFuture = acquireOutboundChannel(inbound, server, routeKey, upstream);
                    outboundFuture.addListener((ChannelFutureListener) f -> {
                        if (!f.isSuccess()) {
                            runOnExecutor(routeExecutor, () -> onRouteInitFailure(routeKey, initState, routeInitMap, f.cause()));
                            return;
                        }

                        Channel outbound = f.channel();
                        CompletableFuture<Void> readyFuture;
                        try {
                            readyFuture = upstream.initChannelAsync(outbound);
                        } catch (Throwable e) {
                            readyFuture = new CompletableFuture<>();
                            readyFuture.completeExceptionally(e);
                        }
                        readyFuture.whenComplete((v, error) -> runOnExecutor(routeExecutor, () -> {
                            if (error != null) {
                                onRouteInitFailure(routeKey, initState, routeInitMap, error);
                                return;
                            }
                            onRouteInitSuccess(inbound, routeExecutor, server, routeKey, routeContext, outboundFuture, initState, routeMap, routeInitMap);
                        }));
                    });
                } catch (Throwable e) {
                    onRouteInitFailure(routeKey, initState, routeInitMap, e);
                }
            }));
        } catch (Throwable e) {
            runOnExecutor(routeExecutor, () -> onRouteInitFailure(routeKey, initState, routeInitMap, e));
        }
    }

    private void onRouteInitSuccess(Channel inbound, EventExecutor routeExecutor, ShadowsocksServer server, RouteKey routeKey, SocksContext context,
            ChannelFuture outboundFuture, RouteInitState initState,
            ConcurrentMap<RouteKey, SocksContext> routeMap,
            ConcurrentMap<RouteKey, RouteInitState> routeInitMap) {
        if (!routeInitMap.remove(routeKey, initState)) {
            releasePending(initState);
            return;
        }
        if (!inbound.isActive()) {
            releasePending(initState);
            return;
        }

        bindRouteContext(inbound, outboundFuture, context);
        bindOutbound(outboundFuture.channel(), inbound, routeKey.source, context.getUpstream());
        registerOutboundRoute(outboundFuture.channel(), routeKey.destination, context);
        routeMap.put(routeKey, context);
        inbound.attr(ATTR_LAST_ROUTE).set(new LastRoute(routeKey.source, routeKey.destination, context));
        recordRouteCacheSizes(inbound, routeMap, outboundFuture.channel());
        outboundFuture.channel().closeFuture().addListener(f -> routeExecutor.execute(() -> {
            routeMap.remove(routeKey, context);
            unregisterOutboundRoute(outboundFuture.channel(), routeKey.destination, context);
            clearLastRoute(inbound, routeKey, context);
            recordRouteCacheSizes(inbound, routeMap, outboundFuture.channel());
        }));
        flushPending(server, routeKey, context, initState);
    }

    private void onRouteInitFailure(RouteKey routeKey, RouteInitState initState,
            ConcurrentMap<RouteKey, RouteInitState> routeInitMap, Throwable error) {
        routeInitMap.remove(routeKey, initState);
        releasePending(initState);
        log.warn("SS UDP async route init fail for {}", routeKey.destination, error);
    }

    private boolean enqueuePendingPacket(RouteInitState initState, ByteBuf inBuf, RouteKey routeKey) {
        int bytes = inBuf.readableBytes();
        if (initState.pendingPackets.size() >= MAX_PENDING_ROUTE_PACKETS
                || initState.pendingBytes + bytes > MAX_PENDING_ROUTE_BYTES) {
            recordUdpDrop("pending-route-overflow", routeKey.source, routeKey.destination, null, bytes);
            log.warn("SS UDP pending route overflow for {} from {}, pendingPackets={}, pendingBytes={}",
                    routeKey.destination, routeKey.source, initState.pendingPackets.size(), initState.pendingBytes);
            return false;
        }
        initState.pendingPackets.addLast(new PendingPacket(inBuf.retain()));
        initState.pendingBytes += bytes;
        return true;
    }

    private void flushPending(ShadowsocksServer server, RouteKey routeKey, SocksContext context, RouteInitState initState) {
        boolean debug = server.config.isDebug();
        PendingPacket packet;
        while ((packet = initState.pendingPackets.pollFirst()) != null) {
            initState.pendingBytes -= packet.content.readableBytes();
            writeRoutePacket(context, routeKey.source, routeKey.destination, packet.content, debug);
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

    static boolean reserveSourcePending(Channel inbound, InetSocketAddress source, int bytes, int limitBytes) {
        if (inbound == null || source == null || bytes <= 0 || limitBytes <= 0) {
            return true;
        }
        AtomicInteger counter = sourcePendingMap(inbound).computeIfAbsent(source, k -> new AtomicInteger());
        int pending = counter.addAndGet(bytes);
        if (pending <= limitBytes) {
            return true;
        }
        releaseSourcePending(inbound, source, bytes);
        return false;
    }

    static void releaseSourcePending(Channel inbound, InetSocketAddress source, int bytes) {
        if (inbound == null || source == null || bytes <= 0) {
            return;
        }
        ConcurrentMap<InetSocketAddress, AtomicInteger> map = inbound.attr(ATTR_UDP_PENDING_WRITE_BYTES_BY_SOURCE).get();
        if (map == null) {
            return;
        }
        AtomicInteger counter = map.get(source);
        if (counter == null) {
            return;
        }
        int pending = counter.addAndGet(-bytes);
        if (pending <= 0) {
            map.remove(source, counter);
        }
    }

    static int sourcePendingBytes(Channel inbound, InetSocketAddress source) {
        ConcurrentMap<InetSocketAddress, AtomicInteger> map = inbound.attr(ATTR_UDP_PENDING_WRITE_BYTES_BY_SOURCE).get();
        if (map == null) {
            return 0;
        }
        AtomicInteger counter = map.get(source);
        return counter == null ? 0 : Math.max(0, counter.get());
    }

    private static ConcurrentMap<InetSocketAddress, AtomicInteger> sourcePendingMap(Channel inbound) {
        ConcurrentMap<InetSocketAddress, AtomicInteger> map = inbound.attr(ATTR_UDP_PENDING_WRITE_BYTES_BY_SOURCE).get();
        if (map != null) {
            return map;
        }
        ConcurrentMap<InetSocketAddress, AtomicInteger> newMap = new ConcurrentHashMap<>();
        ConcurrentMap<InetSocketAddress, AtomicInteger> oldMap = inbound.attr(ATTR_UDP_PENDING_WRITE_BYTES_BY_SOURCE).setIfAbsent(newMap);
        return oldMap != null ? oldMap : newMap;
    }

    @Slf4j
    @ChannelHandler.Sharable
    public static class UdpBackendRelayHandler extends SimpleChannelInboundHandler<DatagramPacket> {
        public static final UdpBackendRelayHandler DEFAULT = new UdpBackendRelayHandler();

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket out) throws Exception {
            Channel outbound = ctx.channel();
            OutboundBinding binding = outbound.attr(ATTR_OUTBOUND_BINDING).get();
            if (binding == null) {
                DiagnosticMetrics.record("ss.udp.drop.count", 1D,
                        "reason=missing-binding,path=backend");
                log.warn("SS UDP backend relay missing outbound binding, drop packet from {}", out.sender());
                return;
            }

            ShadowsocksServer server = Sockets.getAttr(binding.inbound, ShadowsocksConfig.SVR);
            boolean debug = server.config.isDebug();
            InetSocketAddress srcEp = binding.source;
            InetSocketAddress realDstEp;
            UnresolvedEndpoint responseDst = null;
            ByteBuf outBuf = out.content();
            boolean releaseOutBuf = false;
            Upstream representative = binding.representativeUpstream;
            InetSocketAddress udpRelayAddr = relayAddress(representative, outbound);
            if (representative instanceof Udp2rawUpstream) {
                Udp2rawUpstream udp2rawUpstream = (Udp2rawUpstream) representative;
                if (!udp2rawUpstream.ownsUdpEntryAddress(outbound, out.sender())) {
                    DiagnosticMetrics.record("ss.udp.unexpected.sender.count", 1D,
                            "path=backend,mode=udp2raw");
                    log.warn("SS UDP discard packet from unexpected udp2raw sender {}, expected {}", out.sender(), udpRelayAddr);
                    return;
                }
                Udp2rawUpstream.Udp2rawResponse response = udp2rawUpstream.decodeResponse(outbound, out);
                if (response == null) {
                    return;
                }
                outBuf = response.getPayload();
                releaseOutBuf = true;
                realDstEp = response.getSourceAddress();
                responseDst = new UnresolvedEndpoint(realDstEp.getHostString(), realDstEp.getPort());
            } else if (udpRelayAddr != null) {
                boolean udpClientResponse = representative instanceof UdpClientUpstream;
                if (!udpClientResponse && !((SocksUdpUpstream) representative).ownsUdpRelayAddress(outbound, out.sender())) {
                    DiagnosticMetrics.record("ss.udp.unexpected.sender.count", 1D,
                            "path=backend,mode=socks");
                    log.warn("SS UDP discard packet from unexpected relay sender {}, expected group primary {}", out.sender(), udpRelayAddr);
                    return;
                }
                if (udpClientResponse && !((UdpClientUpstream) representative).ownsUdpClientAddress(out.sender())) {
                    DiagnosticMetrics.record("ss.udp.unexpected.sender.count", 1D,
                            "path=backend,mode=udp-client");
                    log.warn("SS UDP discard packet from unexpected udp client sender {}, expected {}", out.sender(), udpRelayAddr);
                    return;
                }
                if (!UdpManager.isValidSocks5UdpPacket(outBuf)) {
                    DiagnosticMetrics.record("ss.udp.drop.count", 1D,
                            "reason=invalid-socks5-relay,path=backend");
                    log.warn("SS UDP discard invalid socks5 relay packet from {}, size={}", out.sender(), outBuf.readableBytes());
                    return;
                }
                if (!udpClientResponse) {
                    ((SocksUdpUpstream) representative).recordUdpTraffic(outbound, outBuf.readableBytes());
                }
                responseDst = UdpManager.socks5Decode(outBuf);
                realDstEp = responseDst.socketAddress();
            } else {
                realDstEp = out.sender();
                responseDst = new UnresolvedEndpoint(realDstEp.getHostString(), realDstEp.getPort());
            }

            UdpManager.HeaderTemplate responseHeader = addressHeaderTemplate(binding.inbound, realDstEp);
            int responseBytes = outBuf.readableBytes() + responseHeader.length();
            if (!reserveSourcePending(binding.inbound, srcEp, responseBytes,
                    server.config.getUdpWritePerSourceLimitBytes())) {
                if (releaseOutBuf) {
                    Bytes.release(outBuf);
                }
                DiagnosticMetrics.record("ss.udp.drop.count", 1D,
                        "reason=per-source-pending-overlimit,path=backend");
                log.warn("SS UDP drop response {} => {} due to per-source pending overlimit bytes={}",
                        realDstEp, srcEp, responseBytes);
                return;
            }

            DatagramPacket packet;
            try {
                packet = new DatagramPacket(UdpManager.prependAddress(ctx.alloc(), outBuf, responseHeader), srcEp);
            } catch (Throwable e) {
                if (releaseOutBuf) {
                    Bytes.release(outBuf);
                }
                releaseSourcePending(binding.inbound, srcEp, responseBytes);
                throw e;
            }
            if (releaseOutBuf) {
                Bytes.release(outBuf);
            }
            Sockets.UdpWriteResult result = Sockets.writeUdp(binding.inbound, packet, server.config, "ss.udp",
                    udpMetricTags("backend", "inbound", binding.representativeUpstream),
                    f -> releaseSourcePending(binding.inbound, srcEp, responseBytes));
            if (result != Sockets.UdpWriteResult.ACCEPTED) {
                releaseSourcePending(binding.inbound, srcEp, responseBytes);
                log.warn("SS UDP drop response {} => {} result={}", realDstEp, srcEp, result);
                return;
            }
            if (debug) {
                SocksContext routeContext = lookupOutboundRoute(outbound, responseDst);
                UnresolvedEndpoint dstEp = routeContext != null ? routeContext.getFirstDestination() : responseDst;
                log.info("SS UDP IN {}[{}] => {}", realDstEp, dstEp, srcEp);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.warn("SS UDP backend relay error", cause);
        }
    }

    public static final SSUdpProxyHandler DEFAULT = new SSUdpProxyHandler();

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket in) throws Exception {
        ByteBuf inBuf = in.content();
        // if (inBuf.readableBytes() < 4) { //no cipher, min size = 1 + 1 + 2 ,[1-byte type][variable-length host][2-byte port]
        // return;
        // }

        Channel inbound = ctx.channel();
        InetSocketAddress srcEp = in.sender();
        UnresolvedEndpoint dstEp = inbound.attr(ShadowsocksConfig.REMOTE_DEST).get();
        ShadowsocksServer server = Sockets.getAttr(inbound, ShadowsocksConfig.SVR);
        ConcurrentMap<RouteKey, SocksContext> routeMap = routeMap(inbound);
        ConcurrentMap<RouteKey, RouteInitState> routeInitMap = routeInitMap(inbound);

        SocksContext e = lookupRoute(inbound, routeMap, srcEp, dstEp);
        if (e != null) {
            writeRoutePacket(e, srcEp, dstEp, inBuf.retain(), server.config.isDebug());
            return;
        }

        RouteKey routeKey = new RouteKey(srcEp, dstEp);
        RouteInitState initState = routeInitMap.get(routeKey);
        boolean created = false;
        if (initState == null) {
            RouteInitState newState = new RouteInitState();
            RouteInitState oldState = routeInitMap.putIfAbsent(routeKey, newState);
            initState = oldState != null ? oldState : newState;
            created = oldState == null;
        }
        if (!enqueuePendingPacket(initState, inBuf, routeKey)) {
            return;
        }
        if (created) {
            beginRouteInit(inbound, ctx.executor(), server, routeKey, initState, routeMap, routeInitMap);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("SS UDP frontend relay error", cause);
    }

    private static ConcurrentMap<RouteKey, SocksContext> routeMap(Channel inbound) {
        ConcurrentMap<RouteKey, SocksContext> routeMap = inbound.attr(ATTR_ROUTE_MAP).get();
        if (routeMap != null) {
            return routeMap;
        }
        ConcurrentMap<RouteKey, SocksContext> newMap = MemoryCache.<RouteKey, SocksContext>rootBuilder().maximumSize(2048).build().asMap();
        ConcurrentMap<RouteKey, SocksContext> oldMap = inbound.attr(ATTR_ROUTE_MAP).setIfAbsent(newMap);
        return oldMap != null ? oldMap : newMap;
    }

    private static ConcurrentMap<RouteKey, RouteInitState> routeInitMap(Channel inbound) {
        ConcurrentMap<RouteKey, RouteInitState> routeInitMap = inbound.attr(ATTR_ROUTE_INIT_MAP).get();
        if (routeInitMap != null) {
            return routeInitMap;
        }
        ConcurrentMap<RouteKey, RouteInitState> newMap = MemoryCache.<RouteKey, RouteInitState>rootBuilder().maximumSize(2048).build().asMap();
        ConcurrentMap<RouteKey, RouteInitState> oldMap = inbound.attr(ATTR_ROUTE_INIT_MAP).setIfAbsent(newMap);
        return oldMap != null ? oldMap : newMap;
    }

    private static boolean removeOutboundPool(OutboundPoolKey key, ChannelFuture chf, String reason) {
        if (OUTBOUND_POOL.remove(key, chf)) {
            releaseOutboundSource(key);
            recordOutboundPoolClose(reason);
            return true;
        }
        return false;
    }

    private static boolean reserveOutboundSource(OutboundPoolKey key, int maxPerSource) {
        if (maxPerSource <= 0) {
            return true;
        }
        AtomicInteger count = OUTBOUND_POOL_SOURCE_COUNTS.computeIfAbsent(key.sourceKey, k -> new AtomicInteger());
        int current = count.incrementAndGet();
        if (current <= maxPerSource) {
            return true;
        }
        releaseOutboundSourceCount(key.sourceKey, count);
        return false;
    }

    private static void releaseOutboundSource(OutboundPoolKey key) {
        if (key.sourceReleased.compareAndSet(false, true)) {
            AtomicInteger count = OUTBOUND_POOL_SOURCE_COUNTS.get(key.sourceKey);
            releaseOutboundSourceCount(key.sourceKey, count);
        }
    }

    private static void releaseOutboundSourceCount(OutboundSourceKey key, AtomicInteger count) {
        if (count == null) {
            return;
        }
        int remaining = count.decrementAndGet();
        if (remaining <= 0) {
            OUTBOUND_POOL_SOURCE_COUNTS.remove(key, count);
        }
    }

    private static void recordOutboundPoolOpen(String result) {
        DiagnosticMetrics.record("ss.udp.outbound.pool.open.count", 1D, "result=" + result);
        DiagnosticMetrics.record("ss.udp.outbound.pool.size", OUTBOUND_POOL.size(), "action=open");
    }

    private static void recordOutboundPoolClose(String reason) {
        DiagnosticMetrics.record("ss.udp.outbound.pool.close.count", 1D, "reason=" + reason);
        DiagnosticMetrics.record("ss.udp.outbound.pool.size", OUTBOUND_POOL.size(), "action=close");
    }

    private static void recordRouteCacheSizes(Channel inbound, ConcurrentMap<RouteKey, SocksContext> routeMap, Channel outbound) {
        DiagnosticMetrics.record("ss.udp.route.cache.size", routeMap.size(),
                "action=update,scope=inbound");
        ConcurrentMap<UnresolvedEndpoint, SocksContext> outboundRouteMap = outbound.attr(ATTR_OUTBOUND_ROUTE_MAP).get();
        if (outboundRouteMap != null) {
            DiagnosticMetrics.record("ss.udp.outbound.route.cache.size", outboundRouteMap.size(),
                    "action=update,scope=outbound");
        }
        MemoryCache<UnresolvedEndpoint, UdpManager.HeaderTemplate> socks5Cache = inbound.attr(ATTR_SOCKS5_HEADER_CACHE).get();
        if (socks5Cache != null) {
            DiagnosticMetrics.record("ss.udp.header.cache.size", socks5Cache.size(),
                    "kind=socks5,scope=inbound");
        }
        MemoryCache<InetSocketAddress, UdpManager.HeaderTemplate> addressCache = inbound.attr(ATTR_ADDRESS_HEADER_CACHE).get();
        if (addressCache != null) {
            DiagnosticMetrics.record("ss.udp.header.cache.size", addressCache.size(),
                    "kind=address,scope=inbound");
        }
    }

    private static void recordUdpDrop(String reason, InetSocketAddress srcEp, UnresolvedEndpoint dstEp,
            InetSocketAddress target, int bytes) {
        DiagnosticMetrics.record("ss.udp.drop.count", 1D, "reason=" + reason + ",path=frontend");
    }

    private static int readableBytesOf(ByteBuf buf) {
        return buf != null && buf.refCnt() > 0 ? buf.readableBytes() : 0;
    }

    private static String udpMetricTags(String path, String direction, Upstream upstream) {
        if ("frontend".equals(path) && "outbound".equals(direction)) {
            if (upstream instanceof SocksUdpUpstream) {
                return UDP_TAG_FRONTEND_OUTBOUND_SOCKS;
            }
            if (upstream instanceof Udp2rawUpstream) {
                return UDP_TAG_FRONTEND_OUTBOUND_UDP2RAW;
            }
            if (upstream instanceof UdpClientUpstream) {
                return UDP_TAG_FRONTEND_OUTBOUND_UDP_CLIENT;
            }
            return upstream != null ? UDP_TAG_FRONTEND_OUTBOUND_DIRECT : UDP_TAG_FRONTEND_OUTBOUND;
        }
        if ("backend".equals(path) && "inbound".equals(direction)) {
            if (upstream instanceof SocksUdpUpstream) {
                return UDP_TAG_BACKEND_INBOUND_SOCKS;
            }
            if (upstream instanceof Udp2rawUpstream) {
                return UDP_TAG_BACKEND_INBOUND_UDP2RAW;
            }
            if (upstream instanceof UdpClientUpstream) {
                return UDP_TAG_BACKEND_INBOUND_UDP_CLIENT;
            }
            return upstream != null ? UDP_TAG_BACKEND_INBOUND_DIRECT : UDP_TAG_BACKEND_INBOUND;
        }

        String flow = "outbound".equals(direction) ? "to-upstream" : "inbound".equals(direction) ? "to-client" : direction;
        String tags = "path=" + path + ",flow=" + flow;
        return upstream != null ? tags + ",mode="
                + (upstream instanceof SocksUdpUpstream ? "socks"
                : upstream instanceof Udp2rawUpstream ? "udp2raw"
                : upstream instanceof UdpClientUpstream ? "udp-client" : "direct") : tags;
    }
}
