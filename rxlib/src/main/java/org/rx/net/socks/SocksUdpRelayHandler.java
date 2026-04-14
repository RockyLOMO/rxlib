package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.AttributeKey;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.cache.MemoryCache;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.Sockets;
import org.rx.net.socks.upstream.SocksUdpUpstream;
import org.rx.net.socks.upstream.Upstream;
import org.rx.net.support.EndpointTracer;
import org.rx.net.support.UnresolvedEndpoint;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentHashMap;

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

    public static final SocksUdpRelayHandler DEFAULT = new SocksUdpRelayHandler();

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
        if (ctxMap != null && ctxMap.containsKey(sender)) {
            handleDestResponse(relay, in, sender, ctxMap);
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
        InetAddress senderIp = sender.getAddress();
        if (!Sockets.isPrivateIp(senderIp) && !config.getWhiteList().contains(senderIp)) {
            log.warn("socks5[{}] UDP security error, packet from {}", config.getListenPort(), sender);
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
        final UnresolvedEndpoint dstEp = UdpManager.socks5Decode(inBuf);
        
        SocksContext e = routeMap.get(dstEp);
        if (e == null) {
            e = SocksContext.getCtx(sender, dstEp);
            server.raiseEvent(server.onUdpRoute, e);
            Upstream upstream = e.getUpstream();
            upstream.initChannel(relay);
            
            InetSocketAddress udpRelayAddr = upstream instanceof SocksUdpUpstream 
                    ? ((SocksUdpUpstream) upstream).getUdpRelayAddress(relay) : null;
            InetSocketAddress upDstAddr = udpRelayAddr != null ? udpRelayAddr : dstEp.socketAddress();
            ctxMap.put(upDstAddr, e);
            routeMap.put(dstEp, e);
        }

        Upstream upstream = e.getUpstream();
        boolean keepSocksHeader = upstream instanceof SocksUdpUpstream && ((SocksUdpUpstream) upstream).getUdpRelayAddress(relay) != null;
        if (keepSocksHeader) {
            inBuf.resetReaderIndex();
        }
        InetSocketAddress upDstAddr = keepSocksHeader ? ((SocksUdpUpstream) upstream).getUdpRelayAddress(relay) : dstEp.socketAddress();

        EndpointTracer.UDP.link(sender, relay);

        inBuf.retain();
        if (config.isDebug()) {
            log.info("socks5[{}] UDP OUT {}bytes {} => {}[{}]",
                    config.getListenPort(), inBuf.readableBytes(), sender, upDstAddr, dstEp);
        }
        relay.writeAndFlush(new DatagramPacket(inBuf, upDstAddr));
    }

    /** Upstream response → Client */
    private void handleDestResponse(Channel relay, DatagramPacket in,
                                    InetSocketAddress sender,
                                    ConcurrentMap<InetSocketAddress, SocksContext> ctxMap) {
        InetSocketAddress clientAddr = relay.attr(ATTR_CLIENT_ADDR).get();
        if (clientAddr == null) {
            return; // no established session yet
        }

        SocksContext sc = ctxMap.get(sender);
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
}
