package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;
import io.netty.channel.*;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.AttributeKey;
import lombok.extern.slf4j.Slf4j;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.Sockets;
import org.rx.net.socks.upstream.Upstream;
import org.rx.net.support.UnresolvedEndpoint;

import java.net.InetSocketAddress;
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
    public static final AttributeKey<ConcurrentHashMap<InetSocketAddress, SocksContext>> ATTR_CTX_MAP =
            AttributeKey.valueOf("udp2rawCtxMap");

    public static final Udp2rawHandler DEFAULT = new Udp2rawHandler();
    static final short STREAM_MAGIC = -21264;
    static final byte STREAM_VERSION = 1;

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket in) throws Exception {
        Channel relay = ctx.channel();
        SocksProxyServer server = Sockets.getAttr(relay, SocksContext.SOCKS_SVR);
        SocksConfig config = server.config;
        InetSocketAddress sender = in.sender();

        InetSocketAddress udp2rawClient = config.getUdp2rawClient();
        if (udp2rawClient != null) {
            // CLIENT MODE: dispatch by udp2rawClient address
            if (udp2rawClient.equals(sender)) {
                handleClientModeResponse(relay, in, config);
            } else {
                handleClientModePacket(ctx, relay, in, sender, udp2rawClient, server, config);
            }
        } else {
            // SERVER MODE: dispatch by ctxMap (known upstream = response)
            ConcurrentHashMap<InetSocketAddress, SocksContext> ctxMap = relay.attr(ATTR_CTX_MAP).get();
            if (ctxMap != null && ctxMap.containsKey(sender)) {
                handleServerModeResponse(relay, in, sender, ctxMap, config);
            } else {
                handleServerModePacket(ctx, relay, in, sender, server, config);
            }
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
        if (clientAddr == null || !clientAddr.equals(sender)) {
            relay.attr(ATTR_CLIENT_ADDR).set(sender);
        }

        InetSocketAddress clientEp = sender;
        final UnresolvedEndpoint dstEp = UdpManager.socks5Decode(inBuf);
        SocksContext e = SocksContext.getCtx(clientEp, dstEp);
        server.raiseEvent(server.onUdpRoute, e);
        Upstream upstream = e.getUpstream();
        UnresolvedEndpoint upDstEp = upstream.getDestination();

        //todo 忽略upstream.getSocksServer()
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
                log.info("UDP2RAW[{}] client send {}bytes {} => {}[{}]", config.getListenPort(), outBuf.readableBytes(), clientEp, udp2rawClient, upDstEp);
            }
            relay.writeAndFlush(new DatagramPacket(outBuf, udp2rawClient));
        } catch (Exception ex) {
            header.release();
            outBuf.release();
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
        if (clientAddr == null || !clientAddr.equals(sender)) {
            relay.attr(ATTR_CLIENT_ADDR).set(sender);
        }

        // Ensure per-relay context map exists
        ConcurrentHashMap<InetSocketAddress, SocksContext> ctxMap = relay.attr(ATTR_CTX_MAP).get();
        if (ctxMap == null) {
            relay.attr(ATTR_CTX_MAP).set(ctxMap = new ConcurrentHashMap<>());
        }

        final UnresolvedEndpoint clientEp = UdpManager.decode(inBuf);
        final UnresolvedEndpoint dstEp = UdpManager.decode(inBuf);
        SocksContext e = SocksContext.getCtx(clientEp.socketAddress(), dstEp);
        e.udp2rawClient = sender;
        server.raiseEvent(server.onUdpRoute, e);
        Upstream upstream = e.getUpstream();

        // Choose upstream destination
        UnresolvedEndpoint upDstEp;
        java.net.InetSocketAddress udpRelayAddr = upstream instanceof org.rx.net.socks.upstream.SocksUdpUpstream 
                ? ((org.rx.net.socks.upstream.SocksUdpUpstream) upstream).getUdpRelayAddress(relay) : null;
        if (udpRelayAddr != null) {
            upDstEp = new UnresolvedEndpoint(udpRelayAddr);
            inBuf.readerIndex(0);
        } else {
            upDstEp = dstEp;
        }

        InetSocketAddress upDstAddr = upDstEp.socketAddress();

        // Register upstream address → context for response demultiplexing
        ctxMap.put(upDstAddr, e);

        if (config.isDebug()) {
            log.info("UDP2RAW[{}] server send {}bytes {}[{}] => {}", config.getListenPort(), inBuf.readableBytes(), sender, clientEp, upDstEp);
        }
        relay.writeAndFlush(new DatagramPacket(inBuf.retain(), upDstAddr));
    }

    /** Server mode: response from real destination → wrap in udp2raw → send back to client */
    private void handleServerModeResponse(Channel relay, DatagramPacket in,
                                          InetSocketAddress sender,
                                          ConcurrentHashMap<InetSocketAddress, SocksContext> ctxMap,
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
        } catch (Exception e) {
            header.release();
            outBufCom.release();
            throw e;
        }
    }

    //endregion
}
