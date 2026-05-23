package org.rx.net.socks;

import org.rx.net.udp.*;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;
import io.netty.channel.*;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.AttributeKey;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.cache.MemoryCache;
import org.rx.io.Bytes;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.Sockets;
import org.rx.net.socks.upstream.SocksUdpUpstream;
import org.rx.net.socks.upstream.Upstream;
import org.rx.net.support.EndpointTracer;
import org.rx.net.support.UnresolvedEndpoint;

import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentMap;

/**
 * Per-client UDP relay handler for udp2raw protocol, installed on a dedicated UDP channel
 * created by {@link Socks5CommandRequestHandler} during UDP_ASSOCIATE handshake.
 *
 * <p>Follows the same lifecycle pattern as {@link SocksUdpRelayHandler}:
 * one channel per TCP control connection.
 *
 * <p>Client mode ({@code config.getUdp2rawClient() != null}):
 * <ul>
 *   <li>Client → upstream: decode SOCKS5 header, wrap in udp2raw protocol, send to udp2rawClient server</li>
 *   <li>Upstream → client: receive from udp2rawClient server, unwrap udp2raw, send SOCKS5 data back</li>
 * </ul>
 *
 * <p>Server mode ({@code config.getUdp2rawClient() == null}):
 * <ul>
 *   <li>Client → upstream: receive udp2raw packet, unwrap, route and send to real destination</li>
 *   <li>Upstream → client: receive from real destination, wrap in udp2raw, send back to client</li>
 * </ul>
 */
@Slf4j
@ChannelHandler.Sharable
public class Udp2rawHandler extends SimpleChannelInboundHandler<DatagramPacket> {
    /** The udp2raw client address confirmed by the first UDP packet. */
    public static final AttributeKey<InetSocketAddress> ATTR_CLIENT_ADDR =
            AttributeKey.valueOf("udp2rawClientAddr");

    /**
     * Per-relay context map: upstream InetSocketAddress → SocksContext.
     * Keyed by the resolved upstream destination (IP+port) so inbound
     * responses can be demultiplexed back to the originating client session.
     */
    public static final AttributeKey<ConcurrentMap<InetSocketAddress, SocksContext>> ATTR_CTX_MAP =
            AttributeKey.valueOf("udp2rawCtxMap");

    public static final AttributeKey<ConcurrentMap<UnresolvedEndpoint, SocksContext>> ATTR_ROUTE_MAP =
            AttributeKey.valueOf("udp2rawRouteMap");

    public static final Udp2rawHandler DEFAULT = new Udp2rawHandler();
    private static final String METRIC_PREFIX = "socks.udp2raw";
    static final short STREAM_MAGIC = -21264;
    static final byte STREAM_VERSION = 1;

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket in) throws Exception {
        Channel relay = ctx.channel();
        SocksProxyServer server = Sockets.getAttr(relay, SocksContext.SOCKS_SVR);
        SocksConfig config = server.config;
        ByteBuf inBuf = in.content();
        InetSocketAddress sender = in.sender();

        // SERVER MODE: dispatch by ctxMap (known upstream = response)
        ConcurrentMap<InetSocketAddress, SocksContext> ctxMap = relay.attr(ATTR_CTX_MAP).get();
        SocksContext context = ctxMap != null ? ctxMap.get(sender) : null;
        if (context != null) {
            // If response from next-hop proxy is already wrapped, unwrap it; otherwise wrap the dest response
            if (inBuf.readableBytes() >= 3 && inBuf.getShort(inBuf.readerIndex()) == STREAM_MAGIC) {
                handleClientModeResponse(relay, in, context, config);
            } else {
                handleServerModeResponse(relay, in, sender, context, config);
            }
            return;
        }

        // Outbound Requests from Client
        if (inBuf.readableBytes() >= 3 && inBuf.getShort(inBuf.readerIndex()) == STREAM_MAGIC) {
            handleServerModePacket(ctx, relay, in, sender, server, config);
        } else {
            handleClientModePacket(ctx, relay, in, sender, config.getUdp2rawClient(), server, config);
        }
    }

    //region Client mode

    /** Client mode: local SOCKS5 app → wrap in udp2raw → send to udp2rawClient server */
    private void handleClientModePacket(ChannelHandlerContext ctx, Channel relay,
                                        DatagramPacket in, InetSocketAddress sender,
                                        InetSocketAddress udp2rawClient,
                                        SocksProxyServer server, SocksConfig config) {
        ByteBuf inBuf = in.content();
        if (inBuf.readableBytes() < 4) {
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

        InetSocketAddress clientEp = EndpointTracer.UDP.find(sender);
        if (clientEp == null) {
            clientEp = sender;
        }
        relay.attr(UdpRelayAttributes.ATTR_CLIENT_ORIGIN_ADDR).set(clientEp);
        
        ConcurrentMap<UnresolvedEndpoint, SocksContext> routeMap = relay.attr(ATTR_ROUTE_MAP).get();
        if (routeMap == null) {
            relay.attr(ATTR_ROUTE_MAP).set(routeMap = MemoryCache.<UnresolvedEndpoint, SocksContext>rootBuilder().maximumSize(256).build().asMap());
        }

        inBuf.markReaderIndex();
        final UnresolvedEndpoint dstEp = UdpManager.socks5Decode(inBuf);
        
        SocksContext e = routeMap.get(dstEp);
        if (e == null) {
            e = SocksContext.getCtx(clientEp, dstEp);
            SocksUserTraffic.attachFromChannel(e, relay);
            server.publishEvent(server.onUdpRoute, e);
            routeMap.put(dstEp, e);
        }
        final SocksContext routeContext = e;
        final InetSocketAddress finalClientEp = clientEp;
        final ByteBuf payload = inBuf.retain();
        routeContext.getUpstream().initChannelAsync(relay).whenComplete((v, error) -> relay.eventLoop().execute(() -> {
            if (error != null) {
                Bytes.release(payload);
                log.warn("UDP2RAW[{}] client init upstream fail for {}", config.getListenPort(), dstEp, error);
                return;
            }
            writeClientModePacket(ctx, relay, payload, finalClientEp, routeContext, udp2rawClient, config);
        }));
    }

    /** Client mode: response from udp2rawClient server → unwrap udp2raw → send back to local app */
    private void handleClientModeResponse(Channel relay, DatagramPacket in, SocksContext context, SocksConfig config) {
        InetSocketAddress srcEp = in.sender();
        ByteBuf inBuf = in.content();

        if (inBuf.readShort() != STREAM_MAGIC | inBuf.readByte() != STREAM_VERSION) {
            log.warn("UDP2RAW[{}] client discard {} bytes", config.getListenPort(), inBuf.readableBytes());
            return;
        }
        final UnresolvedEndpoint clientEp = UdpManager.decode(inBuf);
        if (config.isDebug()) {
            log.info("UDP2RAW[{}] client recv {}bytes {} => {}", config.getListenPort(), inBuf.readableBytes(), srcEp, clientEp);
        }
        DatagramPacket packet = new DatagramPacket(inBuf.retain(), clientEp.socketAddress());
        SocksUserTraffic.recordRead(relay, context, packet.content().readableBytes(), 1L);
        Sockets.UdpWriteResult result = Sockets.writeUdp(relay, packet, config, METRIC_PREFIX,
                "flow=client-response");
        if (result != Sockets.UdpWriteResult.ACCEPTED) {
            log.warn("UDP2RAW[{}] client drop response {} => {} result={}",
                    config.getListenPort(), srcEp, clientEp, result);
        }
    }

    //endregion

    //region Server mode

    /** Server mode: udp2raw packet from client → unwrap → route → send to real destination */
    private void handleServerModePacket(ChannelHandlerContext ctx, Channel relay,
                                        DatagramPacket in, InetSocketAddress sender,
                                        SocksProxyServer server, SocksConfig config) {
        ByteBuf inBuf = in.content();

        if (inBuf.readShort() != STREAM_MAGIC | inBuf.readByte() != STREAM_VERSION) {
            log.warn("UDP2RAW[{}] server discard {} bytes", config.getListenPort(), inBuf.readableBytes());
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

        // Ensure per-relay context map exists
        ConcurrentMap<InetSocketAddress, SocksContext> ctxMap = relay.attr(ATTR_CTX_MAP).get();
        if (ctxMap == null) {
            relay.attr(ATTR_CTX_MAP).set(ctxMap = MemoryCache.<InetSocketAddress, SocksContext>rootBuilder().maximumSize(256).build().asMap());
        }

        ConcurrentMap<UnresolvedEndpoint, SocksContext> routeMap = relay.attr(ATTR_ROUTE_MAP).get();
        if (routeMap == null) {
            relay.attr(ATTR_ROUTE_MAP).set(routeMap = MemoryCache.<UnresolvedEndpoint, SocksContext>rootBuilder().maximumSize(256).build().asMap());
        }

        inBuf.markReaderIndex();
        final UnresolvedEndpoint clientEp = UdpManager.decode(inBuf);
        final UnresolvedEndpoint dstEp = UdpManager.decode(inBuf);
        
        SocksContext e = routeMap.get(dstEp);
        if (e == null) {
            e = SocksContext.getCtx(clientEp.socketAddress(), dstEp);
            SocksUserTraffic.attachFromChannel(e, relay);
            server.publishEvent(server.onUdpRoute, e);
            routeMap.put(dstEp, e);
        }
        final SocksContext routeContext = e;
        final ConcurrentMap<InetSocketAddress, SocksContext> finalCtxMap = ctxMap;
        final ByteBuf payload = inBuf.retain();
        routeContext.getUpstream().initChannelAsync(relay).whenComplete((v, error) -> relay.eventLoop().execute(() -> {
            if (error != null) {
                Bytes.release(payload);
                log.warn("UDP2RAW[{}] server init upstream fail for {}", config.getListenPort(), dstEp, error);
                return;
            }
            writeServerModePacket(relay, payload, sender, clientEp, dstEp, routeContext, finalCtxMap, config);
        }));
    }

    /** Server mode: response from real destination → wrap in udp2raw → send back to client */
    private void handleServerModeResponse(Channel relay, DatagramPacket in,
                                          InetSocketAddress sender,
                                          SocksContext sc,
                                          SocksConfig config) {
        InetSocketAddress clientAddr = relay.attr(ATTR_CLIENT_ADDR).get();
        if (clientAddr == null) {
            return; // no established session yet
        }

        ByteBuf outBuf = in.content();
        InetSocketAddress dstEp = sender;

        if (sc != null && sc.getUpstream() instanceof SocksUdpUpstream && ((SocksUdpUpstream) sc.getUpstream()).getUdpRelayAddress(relay) != null) {
            outBuf.retain();
        } else {
            outBuf = UdpManager.socks5Encode(outBuf.retain(), dstEp);
        }

        ByteBufAllocator allocator = relay.alloc();
        ByteBuf header = allocator.directBuffer(128);
        CompositeByteBuf outBufCom = allocator.compositeDirectBuffer(2);
        try {
            header.writeShort(STREAM_MAGIC);
            header.writeByte(STREAM_VERSION);
            UdpManager.encode(header, sc != null ? sc.getSource() : clientAddr);
            outBufCom.addComponents(true, header, outBuf);
            if (config.isDebug()) {
                log.info("UDP2RAW[{}] server recv {}bytes {} => {}", config.getListenPort(), outBufCom.readableBytes(), dstEp, clientAddr);
            }
            int trafficBytes = outBufCom.readableBytes();
            if (sc != null && sc.getUpstream() instanceof SocksUdpUpstream) {
                ((SocksUdpUpstream) sc.getUpstream()).recordUdpTraffic(relay, trafficBytes);
            }
            SocksUserTraffic.recordRead(relay, sc, trafficBytes, 1L);
            DatagramPacket packet = new DatagramPacket(outBufCom, clientAddr);
            outBufCom = null;
            Sockets.UdpWriteResult result = Sockets.writeUdp(relay, packet, config, METRIC_PREFIX,
                    "flow=server-response");
            if (result != Sockets.UdpWriteResult.ACCEPTED) {
                log.warn("UDP2RAW[{}] server drop response {} => {} result={}",
                        config.getListenPort(), dstEp, clientAddr, result);
            }
        } catch (Throwable e) {
            Bytes.release(outBufCom);
            throw e;
        }
    }

    private void writeClientModePacket(ChannelHandlerContext ctx, Channel relay, ByteBuf payload,
                                       InetSocketAddress clientEp, SocksContext context,
                                       InetSocketAddress udp2rawClient, SocksConfig config) {
        Upstream upstream = context.getUpstream();
        InetSocketAddress targetAddr = upstream instanceof SocksUdpUpstream
                ? ((SocksUdpUpstream) upstream).selectUdpRelayAddress(relay) : null;
        if (targetAddr == null) {
            targetAddr = udp2rawClient;
        }
        if (targetAddr == null) {
            Bytes.release(payload);
            log.warn("UDP2RAW[{}] client relay not ready for {}", config.getListenPort(), upstream.getDestination());
            return;
        }

        if (UdpRedundantSupport.isConfigured(config) && UdpRedundantSupport.allowUdp2rawRequest(config)) {
            UdpRelayAttributes.addRedundantPeer(relay, targetAddr);
        }
        ConcurrentMap<InetSocketAddress, SocksContext> ctxMap = relay.attr(ATTR_CTX_MAP).get();
        if (ctxMap == null) {
            relay.attr(ATTR_CTX_MAP).set(ctxMap = MemoryCache.<InetSocketAddress, SocksContext>rootBuilder().maximumSize(256).build().asMap());
        }
        registerRelayTargets(relay, upstream, targetAddr, context, ctxMap);

        UnresolvedEndpoint upDstEp = upstream.getDestination();
        ByteBufAllocator allocator = ctx.alloc();
        ByteBuf header = allocator.directBuffer(128);
        CompositeByteBuf outBuf = allocator.compositeDirectBuffer(2);
        try {
            header.writeShort(STREAM_MAGIC);
            header.writeByte(STREAM_VERSION);
            UdpManager.encode(header, clientEp);
            UdpManager.encode(header, upDstEp);
            outBuf.addComponents(true, header, payload);
            if (config.isDebug()) {
                log.info("UDP2RAW[{}] client send {}bytes {} => {}[{}]", config.getListenPort(), outBuf.readableBytes(), clientEp, targetAddr, upDstEp);
            }
            int trafficBytes = outBuf.readableBytes();
            if (upstream instanceof SocksUdpUpstream) {
                ((SocksUdpUpstream) upstream).recordUdpTraffic(relay, trafficBytes);
            }
            SocksUserTraffic.recordWrite(relay, context, trafficBytes, 1L);
            DatagramPacket packet = new DatagramPacket(outBuf, targetAddr);
            outBuf = null;
            Sockets.UdpWriteResult result = Sockets.writeUdp(relay, packet, config, METRIC_PREFIX,
                    "flow=client-request");
            if (result != Sockets.UdpWriteResult.ACCEPTED) {
                log.warn("UDP2RAW[{}] client drop request {} => {}[{}] result={}",
                        config.getListenPort(), clientEp, targetAddr, upDstEp, result);
            }
        } catch (Throwable ex) {
            Bytes.release(outBuf);
            throw ex;
        }
    }

    private void writeServerModePacket(Channel relay, ByteBuf payload, InetSocketAddress sender,
                                       UnresolvedEndpoint clientEp, UnresolvedEndpoint dstEp,
                                       SocksContext context, ConcurrentMap<InetSocketAddress, SocksContext> ctxMap,
                                       SocksConfig config) {
        Upstream upstream = context.getUpstream();
        InetSocketAddress udpRelayAddr = upstream instanceof SocksUdpUpstream
                ? ((SocksUdpUpstream) upstream).selectUdpRelayAddress(relay) : null;
        InetSocketAddress upDstAddr = udpRelayAddr != null ? udpRelayAddr : upstream.getDestination().socketAddress();
        registerRelayTargets(relay, upstream, upDstAddr, context, ctxMap);

        ByteBuf outBuf;
        UnresolvedEndpoint upDstEp;
        if (udpRelayAddr != null) {
            upDstEp = new UnresolvedEndpoint(udpRelayAddr);
            outBuf = UdpManager.socks5Encode(payload, dstEp);
        } else {
            upDstEp = upstream.getDestination();
            outBuf = payload;
        }

        if (config.isDebug()) {
            log.info("UDP2RAW[{}] server send {}bytes {}[{}] => {}", config.getListenPort(), outBuf.readableBytes(), sender, clientEp, upDstEp);
        }
        int trafficBytes = outBuf.readableBytes();
        if (upstream instanceof SocksUdpUpstream) {
            ((SocksUdpUpstream) upstream).recordUdpTraffic(relay, trafficBytes);
        }
        SocksUserTraffic.recordWrite(relay, context, trafficBytes, 1L);
        Sockets.UdpWriteResult result = Sockets.writeUdp(relay, new DatagramPacket(outBuf, upDstAddr),
                config, METRIC_PREFIX, "flow=server-request");
        if (result != Sockets.UdpWriteResult.ACCEPTED) {
            log.warn("UDP2RAW[{}] server drop request {}[{}] => {} result={}",
                    config.getListenPort(), sender, clientEp, upDstEp, result);
        }
    }

    private static void registerRelayTargets(Channel relay, Upstream upstream, InetSocketAddress selected,
                                             SocksContext context,
                                             ConcurrentMap<InetSocketAddress, SocksContext> ctxMap) {
        if (upstream instanceof SocksUdpUpstream) {
            InetSocketAddress[] relayAddresses = ((SocksUdpUpstream) upstream).snapshotUdpRelayAddresses(relay);
            if (relayAddresses.length == 0) {
                ctxMap.put(selected, context);
                return;
            }
            for (InetSocketAddress relayAddress : relayAddresses) {
                ctxMap.put(relayAddress, context);
            }
            return;
        }
        ctxMap.put(selected, context);
    }

    //endregion
}
