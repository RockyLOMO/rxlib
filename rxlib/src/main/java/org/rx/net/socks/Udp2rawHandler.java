package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;
import io.netty.channel.*;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.AttributeKey;
import lombok.extern.slf4j.Slf4j;
import org.rx.io.Bytes;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.Sockets;
import org.rx.net.socks.upstream.Upstream;
import org.rx.net.support.UnresolvedEndpoint;

import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentHashMap;

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
        if (ctxMap != null && ctxMap.containsKey(sender)) {
            // If response from next-hop proxy is already wrapped, unwrap it; otherwise wrap the dest response
            if (inBuf.readableBytes() >= 3 && inBuf.getShort(inBuf.readerIndex()) == STREAM_MAGIC) {
                handleClientModeResponse(relay, in, config);
            } else {
                handleServerModeResponse(relay, in, sender, ctxMap, config);
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

        InetSocketAddress clientEp = sender;
        
        ConcurrentMap<UnresolvedEndpoint, SocksContext> routeMap = relay.attr(ATTR_ROUTE_MAP).get();
        if (routeMap == null) {
            relay.attr(ATTR_ROUTE_MAP).set(routeMap = com.github.benmanes.caffeine.cache.Caffeine.newBuilder().maximumSize(256).<UnresolvedEndpoint, SocksContext>build().asMap());
        }

        inBuf.markReaderIndex();
        final UnresolvedEndpoint dstEp = UdpManager.socks5Decode(inBuf);
        
        SocksContext e = routeMap.get(dstEp);
        if (e == null) {
            e = SocksContext.getCtx(clientEp, dstEp);
            server.raiseEvent(server.onUdpRoute, e);
            e.getUpstream().initChannel(relay);
            routeMap.put(dstEp, e);
        }
        
        Upstream upstream = e.getUpstream();
        InetSocketAddress targetAddr = upstream instanceof org.rx.net.socks.upstream.SocksUdpUpstream
                ? ((org.rx.net.socks.upstream.SocksUdpUpstream) upstream).getUdpRelayAddress(relay) : null;
        if (targetAddr == null) {
            targetAddr = udp2rawClient;
        }

        if (targetAddr != null) {
            ConcurrentMap<InetSocketAddress, SocksContext> ctxMap = relay.attr(ATTR_CTX_MAP).get();
            if (ctxMap == null) {
                relay.attr(ATTR_CTX_MAP).set(ctxMap = com.github.benmanes.caffeine.cache.Caffeine.newBuilder().maximumSize(256).<InetSocketAddress, SocksContext>build().asMap());
            }
            ctxMap.put(targetAddr, e);
        }

        UnresolvedEndpoint upDstEp = upstream.getDestination();
        ByteBufAllocator allocator = ctx.alloc();
        ByteBuf header = allocator.directBuffer(128);
        CompositeByteBuf outBuf = allocator.compositeDirectBuffer(2);
        try {
            header.writeShort(STREAM_MAGIC);
            header.writeByte(STREAM_VERSION);
            UdpManager.encode(header, clientEp);
            UdpManager.encode(header, upDstEp);
            outBuf.addComponents(true, header, inBuf.retain());
            if (config.isDebug()) {
                log.info("UDP2RAW[{}] client send {}bytes {} => {}[{}]", config.getListenPort(), outBuf.readableBytes(), clientEp, targetAddr, upDstEp);
            }
            relay.writeAndFlush(new DatagramPacket(outBuf, targetAddr));
        } catch (Throwable ex) {
            Bytes.release(header);
            Bytes.release(outBuf);
            throw ex;
        }
    }

    /** Client mode: response from udp2rawClient server → unwrap udp2raw → send back to local app */
    private void handleClientModeResponse(Channel relay, DatagramPacket in, SocksConfig config) {
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
        relay.writeAndFlush(new DatagramPacket(inBuf.retain(), clientEp.socketAddress()));
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

        // Ensure per-relay context map exists
        ConcurrentMap<InetSocketAddress, SocksContext> ctxMap = relay.attr(ATTR_CTX_MAP).get();
        if (ctxMap == null) {
            relay.attr(ATTR_CTX_MAP).set(ctxMap = com.github.benmanes.caffeine.cache.Caffeine.newBuilder().maximumSize(256).<InetSocketAddress, SocksContext>build().asMap());
        }

        ConcurrentMap<UnresolvedEndpoint, SocksContext> routeMap = relay.attr(ATTR_ROUTE_MAP).get();
        if (routeMap == null) {
            relay.attr(ATTR_ROUTE_MAP).set(routeMap = com.github.benmanes.caffeine.cache.Caffeine.newBuilder().maximumSize(256).<UnresolvedEndpoint, SocksContext>build().asMap());
        }

        inBuf.markReaderIndex();
        final UnresolvedEndpoint clientEp = UdpManager.decode(inBuf);
        final UnresolvedEndpoint dstEp = UdpManager.decode(inBuf);
        
        SocksContext e = routeMap.get(dstEp);
        if (e == null) {
            e = SocksContext.getCtx(clientEp.socketAddress(), dstEp);
            server.raiseEvent(server.onUdpRoute, e);
            e.getUpstream().initChannel(relay);
            
            // Register upstream address → context for response demultiplexing
            Upstream upstream = e.getUpstream();
            InetSocketAddress udpRelayAddr = upstream instanceof org.rx.net.socks.upstream.SocksUdpUpstream 
                    ? ((org.rx.net.socks.upstream.SocksUdpUpstream) upstream).getUdpRelayAddress(relay) : null;
            InetSocketAddress upDstAddr = udpRelayAddr != null ? udpRelayAddr : dstEp.socketAddress();
            ctxMap.put(upDstAddr, e);
            routeMap.put(dstEp, e);
        }

        Upstream upstream = e.getUpstream();

        // Choose upstream destination
        UnresolvedEndpoint upDstEp;
        boolean keepSocksHeader = upstream instanceof org.rx.net.socks.upstream.SocksUdpUpstream && 
                ((org.rx.net.socks.upstream.SocksUdpUpstream) upstream).getUdpRelayAddress(relay) != null;
                
        if (keepSocksHeader) {
            upDstEp = new UnresolvedEndpoint(((org.rx.net.socks.upstream.SocksUdpUpstream) upstream).getUdpRelayAddress(relay));
            inBuf = UdpManager.socks5Encode(inBuf.retain(), dstEp);
        } else {
            upDstEp = dstEp;
            inBuf = inBuf.retain();
        }

        InetSocketAddress upDstAddr = upDstEp.socketAddress();

        if (config.isDebug()) {
            log.info("UDP2RAW[{}] server send {}bytes {}[{}] => {}", config.getListenPort(), inBuf.readableBytes(), sender, clientEp, upDstEp);
        }
        relay.writeAndFlush(new DatagramPacket(inBuf, upDstAddr));
    }

    /** Server mode: response from real destination → wrap in udp2raw → send back to client */
    private void handleServerModeResponse(Channel relay, DatagramPacket in,
                                          InetSocketAddress sender,
                                          ConcurrentMap<InetSocketAddress, SocksContext> ctxMap,
                                          SocksConfig config) {
        InetSocketAddress clientAddr = relay.attr(ATTR_CLIENT_ADDR).get();
        if (clientAddr == null) {
            return; // no established session yet
        }

        SocksContext sc = ctxMap.get(sender);
        ByteBuf outBuf = in.content();
        InetSocketAddress dstEp = sender;

        if (sc != null && sc.getUpstream() instanceof org.rx.net.socks.upstream.SocksUdpUpstream && ((org.rx.net.socks.upstream.SocksUdpUpstream) sc.getUpstream()).getUdpRelayAddress(relay) != null) {
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
            relay.writeAndFlush(new DatagramPacket(outBufCom, clientAddr));
        } catch (Throwable e) {
            Bytes.release(header);
            Bytes.release(outBufCom);
            throw e;
        }
    }

    //endregion
}
