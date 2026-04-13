package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.socket.DatagramPacket;
import lombok.extern.slf4j.Slf4j;
import org.rx.net.Sockets;
import org.rx.net.socks.upstream.Upstream;
import org.rx.net.support.EndpointTracer;
import org.rx.net.support.UnresolvedEndpoint;

import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentMap;
import io.netty.util.AttributeKey;

@Slf4j
@ChannelHandler.Sharable
public class SSUdpProxyHandler extends SimpleChannelInboundHandler<DatagramPacket> {
    public static final AttributeKey<ConcurrentMap<UnresolvedEndpoint, SocksContext>> ATTR_ROUTE_MAP =
            AttributeKey.valueOf("ssUdpRouteMap");
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
            if (sc != null && sc.getUpstream() instanceof org.rx.net.socks.upstream.SocksUdpUpstream && ((org.rx.net.socks.upstream.SocksUdpUpstream) sc.getUpstream()).getUdpRelayAddress(outbound) != null) {
                UnresolvedEndpoint tmp = UdpManager.socks5Decode(outBuf);
                realDstEp = tmp.socketAddress();
            } else {
                realDstEp = out.sender();
            }

            ByteBuf addrBuf = ctx.alloc().buffer(64);
            UdpManager.encode(addrBuf, realDstEp);
            ByteBuf finalBuf = io.netty.buffer.Unpooled.wrappedBuffer(addrBuf, outBuf.retain());

            sc.inbound.writeAndFlush(new DatagramPacket(finalBuf, srcEp));
            if (debug) {
                log.info("SS UDP IN {}[{}] => {}", realDstEp, dstEp, srcEp);
            }
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
        UnresolvedEndpoint dstEp = new UnresolvedEndpoint(inbound.attr(ShadowsocksConfig.REMOTE_DEST).get());
        ShadowsocksServer server = Sockets.getAttr(inbound, ShadowsocksConfig.SVR);
        boolean debug = server.config.isDebug();

        ConcurrentMap<UnresolvedEndpoint, SocksContext> routeMap = inbound.attr(ATTR_ROUTE_MAP).get();
        if (routeMap == null) {
            inbound.attr(ATTR_ROUTE_MAP).set(routeMap = com.github.benmanes.caffeine.cache.Caffeine.newBuilder().maximumSize(256).<UnresolvedEndpoint, SocksContext>build().asMap());
        }

        SocksContext e = routeMap.get(dstEp);
        if (e == null) {
            e = SocksContext.getCtx(srcEp, dstEp);
            server.raiseEvent(server.onUdpRoute, e);
            Upstream upstream = e.getUpstream();
            SocksContext finalE = e;
            ChannelFuture outboundFuture = UdpManager.open(UdpManager.ssRegion, srcEp, upstream.getConfig(), k -> {
                ChannelFuture chf = Sockets.udpBootstrap(upstream.getConfig(), ob -> {
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
        EndpointTracer.UDP.link(srcEp, outbound);

        UnresolvedEndpoint upDstEp;
        java.net.InetSocketAddress udpRelayAddr = upstream instanceof org.rx.net.socks.upstream.SocksUdpUpstream 
                ? ((org.rx.net.socks.upstream.SocksUdpUpstream) upstream).getUdpRelayAddress(outbound) : null;
        inBuf.retain();
        if (udpRelayAddr != null) {
            inBuf = UdpManager.socks5Encode(inBuf, dstEp);
            upDstEp = new UnresolvedEndpoint(udpRelayAddr);
        } else {
            upDstEp = upstream.getDestination();
        }
        if (e.outboundActive) {
            outbound.writeAndFlush(new DatagramPacket(inBuf, upDstEp.socketAddress()));
        } else {
            ByteBuf finalInBuf = inBuf;
            e.outbound.addListener((ChannelFutureListener) f -> f.channel().writeAndFlush(new DatagramPacket(finalInBuf, upDstEp.socketAddress())));
        }
        if (debug) {
            log.info("SS UDP OUT {} => {}[{}]", srcEp, upDstEp, dstEp);
        }
    }
}
