package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.AttributeKey;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.cache.MemoryCache;
import org.rx.net.Sockets;
import org.rx.net.socks.upstream.SocksUdpUpstream;
import org.rx.net.socks.upstream.Upstream;
import org.rx.net.support.EndpointTracer;
import org.rx.net.support.UnresolvedEndpoint;

import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentMap;

@Slf4j
@ChannelHandler.Sharable
public class SSUdpProxyHandler extends SimpleChannelInboundHandler<DatagramPacket> {
    public static final AttributeKey<ConcurrentMap<UnresolvedEndpoint, SocksContext>> ATTR_ROUTE_MAP =
            AttributeKey.valueOf("ssUdpRouteMap");

    private static InetSocketAddress relayAddress(Upstream upstream, Channel channel) {
        return upstream instanceof SocksUdpUpstream ? ((SocksUdpUpstream) upstream).getUdpRelayAddress(channel) : null;
    }

    private static DatagramPacket buildOutboundPacket(SocksContext sc, Channel outbound, UnresolvedEndpoint dstEp, ByteBuf payload) {
        Upstream upstream = sc.getUpstream();
        InetSocketAddress udpRelayAddr = relayAddress(upstream, outbound);
        if (upstream instanceof SocksUdpUpstream) {
            if (udpRelayAddr == null) {
                return null;
            }
            return new DatagramPacket(UdpManager.socks5Encode(payload, dstEp), udpRelayAddr);
        }
        return new DatagramPacket(payload, upstream.getDestination().socketAddress());
    }

    @Slf4j
    @ChannelHandler.Sharable
    public static class UdpBackendRelayHandler extends SimpleChannelInboundHandler<DatagramPacket> {
        public static final UdpBackendRelayHandler DEFAULT = new UdpBackendRelayHandler();

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket out) throws Exception {
            Channel outbound = ctx.channel();
            SocksContext sc = SocksContext.ctx(outbound);
            ShadowsocksServer server = Sockets.getAttr(sc.inbound, ShadowsocksConfig.SVR);
            boolean debug = server.config.isDebug();
            InetSocketAddress srcEp = sc.getSource();
            UnresolvedEndpoint dstEp = sc.getFirstDestination();
            InetSocketAddress realDstEp;
            ByteBuf outBuf = out.content();
            InetSocketAddress udpRelayAddr = relayAddress(sc.getUpstream(), outbound);
            if (udpRelayAddr != null) {
                if (!udpRelayAddr.equals(out.sender())) {
                    log.warn("SS UDP discard packet from unexpected relay sender {}, expected {}", out.sender(), udpRelayAddr);
                    return;
                }
                if (!UdpManager.isValidSocks5UdpPacket(outBuf)) {
                    log.warn("SS UDP discard invalid socks5 relay packet from {}, size={}", out.sender(), outBuf.readableBytes());
                    return;
                }
                UnresolvedEndpoint tmp = UdpManager.socks5Decode(outBuf);
                realDstEp = tmp.socketAddress();
            } else {
                realDstEp = out.sender();
            }

            sc.inbound.writeAndFlush(new DatagramPacket(UdpManager.prependAddress(ctx.alloc(), outBuf, realDstEp), srcEp));
            if (debug) {
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
//        if (inBuf.readableBytes() < 4) { //no cipher, min size = 1 + 1 + 2 ,[1-byte type][variable-length host][2-byte port]
//            return;
//        }

        Channel inbound = ctx.channel();
        InetSocketAddress srcEp = in.sender();
        UnresolvedEndpoint dstEp = inbound.attr(ShadowsocksConfig.REMOTE_DEST).get();
        ShadowsocksServer server = Sockets.getAttr(inbound, ShadowsocksConfig.SVR);
        boolean debug = server.config.isDebug();

        ConcurrentMap<UnresolvedEndpoint, SocksContext> routeMap = routeMap(inbound);

        SocksContext e = routeMap.get(dstEp);
        if (e == null) {
            e = SocksContext.getCtx(srcEp, dstEp);
            server.raiseEvent(server.onUdpRoute, e);
            Upstream upstream = e.getUpstream();
            SocksContext finalE = e;
            ChannelFuture outboundFuture = UdpManager.open(UdpManager.ssRegion, srcEp, upstream.getConfig(), k -> {
                ChannelFuture chf = Sockets.udpBootstrap(upstream.getConfig(), ob -> {
                    ob.attr(UdpRelayAttributes.ATTR_CLIENT_ORIGIN_ADDR).set(srcEp);
                    upstream.initChannel(ob);
                    if (server.config.getUdpReadTimeoutSeconds() > 0 || server.config.getUdpWriteTimeoutSeconds() > 0) {
                        ob.pipeline().addLast(new ProxyChannelIdleHandler(server.config.getUdpReadTimeoutSeconds(), server.config.getUdpWriteTimeoutSeconds()));
                    }
                    ob.pipeline().addLast(UdpBackendRelayHandler.DEFAULT);
                }).attr(ShadowsocksConfig.SVR, server).bind(0);
                chf.channel().closeFuture().addListener(f -> UdpManager.close(k));
                return chf;
            });
            SocksContext.markCtx(inbound, outboundFuture, e);
            routeMap.put(dstEp, e);
        }

        Upstream upstream = e.getUpstream();
        Channel outbound = e.outbound.channel();
        final SocksContext finalE = e;
        EndpointTracer.UDP.link(srcEp, outbound);
        inBuf.retain();
        if (e.outboundActive) {
            DatagramPacket packet = null;
            try {
                packet = buildOutboundPacket(e, outbound, dstEp, inBuf);
            } catch (Throwable ex) {
                log.warn("SS UDP relay build packet error for {}, drop packet from {}", dstEp, srcEp, ex);
            }
            if (packet == null) {
                inBuf.release();
                log.warn("SS UDP relay not ready for {}, drop packet from {}", dstEp, srcEp);
                return;
            }
            outbound.writeAndFlush(packet);
        } else {
            ByteBuf finalInBuf = inBuf;
            e.outbound.addListener((ChannelFutureListener) f -> {
                if (!f.isSuccess()) {
                    finalInBuf.release();
                    return;
                }
                DatagramPacket packet = null;
                try {
                    packet = buildOutboundPacket(finalE, f.channel(), dstEp, finalInBuf);
                } catch (Throwable ex) {
                    log.warn("SS UDP relay build packet error for {}, drop packet from {}", dstEp, srcEp, ex);
                }
                if (packet == null) {
                    finalInBuf.release();
                    log.warn("SS UDP relay not ready after bind for {}, drop packet from {}", dstEp, srcEp);
                    return;
                }
                f.channel().writeAndFlush(packet);
            });
        }
        if (debug) {
            InetSocketAddress udpRelayAddr = relayAddress(upstream, outbound);
            log.info("SS UDP OUT {} => {}[{}]", srcEp, udpRelayAddr != null ? udpRelayAddr : upstream.getDestination(), dstEp);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("SS UDP frontend relay error", cause);
    }

    private static ConcurrentMap<UnresolvedEndpoint, SocksContext> routeMap(Channel inbound) {
        ConcurrentMap<UnresolvedEndpoint, SocksContext> routeMap = inbound.attr(ATTR_ROUTE_MAP).get();
        if (routeMap != null) {
            return routeMap;
        }
        ConcurrentMap<UnresolvedEndpoint, SocksContext> newMap = MemoryCache.<UnresolvedEndpoint, SocksContext>rootBuilder().maximumSize(256).build().asMap();
        ConcurrentMap<UnresolvedEndpoint, SocksContext> oldMap = inbound.attr(ATTR_ROUTE_MAP).setIfAbsent(newMap);
        return oldMap != null ? oldMap : newMap;
    }
}
