package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.socket.DatagramPacket;
import lombok.extern.slf4j.Slf4j;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.Sockets;
import org.rx.net.socks.upstream.Upstream;
import org.rx.net.support.UnresolvedEndpoint;

import java.net.InetAddress;
import java.net.InetSocketAddress;

@Slf4j
@ChannelHandler.Sharable
public class SocksUdpRelayHandler extends SimpleChannelInboundHandler<DatagramPacket> {
    @Slf4j
    @ChannelHandler.Sharable
    public static class UdpBackendRelayHandler extends SimpleChannelInboundHandler<DatagramPacket> {
        public static final UdpBackendRelayHandler DEFAULT = new UdpBackendRelayHandler();

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket out) throws Exception {
            Channel outbound = ctx.channel();
            SocksContext sc = SocksContext.ctx(outbound);
            SocksProxyServer server = Sockets.getAttr(sc.inbound, SocksContext.SOCKS_SVR);
            SocksConfig config = server.config;
            InetSocketAddress srcEp = sc.getSource();
//            UnresolvedEndpoint dstEp = sc.firstDestination;
            InetSocketAddress dstEp = out.sender();
            ByteBuf outBuf = out.content();
            if (sc.tryGetUdpSocksServer() != null) {
                outBuf.retain();
            } else {
                outBuf = UdpManager.socks5Encode(outBuf.retain(), dstEp);
            }
            if (config.isDebug()) {
                log.info("socks5[{}] UDP inbound {} => {}", config.getListenPort(), dstEp, srcEp);
            }
            sc.inbound.writeAndFlush(new DatagramPacket(outBuf, srcEp));
        }
    }

    public static final SocksUdpRelayHandler DEFAULT = new SocksUdpRelayHandler();

    /**
     * https://datatracker.ietf.org/doc/html/rfc1928
     * +----+------+------+----------+----------+----------+
     * |RSV | FRAG | ATYP | DST.ADDR | DST.PORT |   DATA   |
     * +----+------+------+----------+----------+----------+
     * | 2  |  1   |  1   | Variable |    2     | Variable |
     * +----+------+------+----------+----------+----------+
     *
     * @param ctx
     * @param in
     * @throws Exception
     */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket in) throws Exception {
        ByteBuf inBuf = in.content();
        if (inBuf.readableBytes() < 4) {
            return;
        }

        Channel inbound = ctx.channel();
        SocksProxyServer server = Sockets.getAttr(inbound, SocksContext.SOCKS_SVR);
        SocksConfig config = server.config;
        final InetSocketAddress srcEp = in.sender();
        InetAddress srcIp = srcEp.getAddress();
        //client in
        if (!Sockets.isPrivateIp(srcIp) && !config.getWhiteList().contains(srcIp)) {
            log.warn("socks5[{}] UDP security error, package from {}", config.getListenPort(), srcEp);
            return;
        }

        //不要尝试UPD白名单，会有未知dstEp发送包的情况
        //不要尝试简化outbound，不改包的情况下srcEp没法关联
        final UnresolvedEndpoint dstEp = UdpManager.socks5Decode(inBuf);
        SocksContext e = SocksContext.getCtx(srcEp, dstEp);
        server.raiseEvent(server.onUdpRoute, e);
        Upstream upstream = e.getUpstream();
        ChannelFuture outboundFuture = UdpManager.open(UdpManager.socksRegion, srcEp, upstream.getConfig(), k -> {
            UdpManager.unsetChannelKey();
            ChannelFuture chf = Sockets.udpBootstrap(upstream.getConfig(), ob -> {
                upstream.initChannel(ob);
                ob.pipeline().addLast(new ProxyChannelIdleHandler(config.getUdpReadTimeoutSeconds(), config.getUdpWriteTimeoutSeconds()),
                        UdpBackendRelayHandler.DEFAULT);
            }).attr(SocksContext.SOCKS_SVR, server).bind(0).addListener(Sockets.logBind(0));
            log.info("socks5[{}] UDP open {}", config.getListenPort(), k);
            chf.channel().closeFuture().addListener(f -> {
                log.info("socks5[{}] UDP close {}", config.getListenPort(), k);
                UdpManager.close(k);
            });
            return chf;
        });
        SocksContext.markCtx(inbound, outboundFuture, e);
        Channel outbound = outboundFuture.channel();

        SocksContext sc = SocksContext.ctx(outbound);
        //udp dstEp可能多个，但upstream.getDestination()只有一个，所以直接用dstEp。
        UnresolvedEndpoint upDstEp;
        AuthenticEndpoint upSvrEp = sc.tryGetUdpSocksServer();
        if (upSvrEp != null) {
            upDstEp = new UnresolvedEndpoint(upSvrEp.getEndpoint());
            inBuf.readerIndex(0);
        } else {
            upDstEp = dstEp;
        }
        inBuf.retain();
        if (sc.outboundActive) {
            if (config.isDebug()) {
                log.info("socks5[{}] UDP outbound {} => {}[{}]", config.getListenPort(), srcEp, upDstEp, dstEp);
            }
            outbound.writeAndFlush(new DatagramPacket(inBuf, upDstEp.socketAddress()));
        } else {
            outboundFuture.addListener((ChannelFutureListener) f -> {
                if (config.isDebug()) {
                    log.info("socks5[{}] UDP outbound pending {} => {}[{}]", config.getListenPort(), srcEp, upDstEp, dstEp);
                }
                f.channel().writeAndFlush(new DatagramPacket(inBuf, upDstEp.socketAddress()));
            });
        }
    }
}
