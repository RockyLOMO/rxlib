package org.rx.net.shadowsocks;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.socket.DatagramPacket;
import lombok.extern.slf4j.Slf4j;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.Sockets;
import org.rx.net.socks.ProxyChannelIdleHandler;
import org.rx.net.socks.SocksContext;
import org.rx.net.socks.UdpManager;
import org.rx.net.socks.upstream.Upstream;
import org.rx.net.support.UnresolvedEndpoint;

import java.net.InetSocketAddress;

@Slf4j
@ChannelHandler.Sharable
public class ServerUdpProxyHandler extends SimpleChannelInboundHandler<DatagramPacket> {
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
            ByteBuf outBuf = out.content();
            if (sc.tryGetUdpSocksServer() != null) {
                UnresolvedEndpoint tmp = UdpManager.socks5Decode(outBuf);
                if (!dstEp.equals(tmp)) {
                    log.error("UDP error dstEp not matched {} != {}", dstEp, tmp);
                }
                sc.inbound.attr(ShadowsocksConfig.REMOTE_SRC).set(tmp.socketAddress());
            } else {
                sc.inbound.attr(ShadowsocksConfig.REMOTE_SRC).set(out.sender());
            }

            sc.inbound.writeAndFlush(new DatagramPacket(outBuf.retain(), srcEp));
            if (debug) {
                log.info("SS UDP IN {}[{}] => {}", out.sender(), dstEp, srcEp);
            }
        }
    }

    public static final ServerUdpProxyHandler DEFAULT = new ServerUdpProxyHandler();

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket in) throws Exception {
        ByteBuf inBuf = in.content();
        if (inBuf.readableBytes() < 4) { //no cipher, min size = 1 + 1 + 2 ,[1-byte type][variable-length host][2-byte port]
            return;
        }

        Channel inbound = ctx.channel();
        InetSocketAddress srcEp = in.sender();
        UnresolvedEndpoint dstEp = new UnresolvedEndpoint(inbound.attr(ShadowsocksConfig.REMOTE_DEST).get());
        ShadowsocksServer server = Sockets.getAttr(inbound, ShadowsocksConfig.SVR);
        boolean debug = server.config.isDebug();

        ChannelFuture outboundFuture = UdpManager.open(srcEp, k -> {
            SocksContext e = new SocksContext(srcEp, dstEp);
            server.raiseEvent(server.onUdpRoute, e);
            Upstream upstream = e.getUpstream();
            ChannelFuture chf = Sockets.udpBootstrap(upstream.getConfig(), ob -> {
                upstream.initChannel(ob);
                ob.pipeline().addLast(new ProxyChannelIdleHandler(server.config.getUdpTimeoutSeconds(), 0),
                        UdpBackendRelayHandler.DEFAULT);
            }).attr(ShadowsocksConfig.SVR, server).bind(0);
            SocksContext.mark(inbound, chf, e);
            chf.channel().closeFuture().addListener(f -> UdpManager.close(srcEp));
            return chf;
        });
        Channel outbound = outboundFuture.channel();

        SocksContext sc = SocksContext.ctx(outbound);
        UnresolvedEndpoint upDstEp;
        AuthenticEndpoint upSvrEp = sc.tryGetUdpSocksServer();
        inBuf.retain();
        if (upSvrEp != null) {
            inBuf = UdpManager.socks5Encode(inBuf, dstEp);
            upDstEp = new UnresolvedEndpoint(upSvrEp.getEndpoint());
        } else {
            upDstEp = sc.getUpstream().getDestination();
        }
        if (sc.isOutboundActive()) {
            outbound.writeAndFlush(new DatagramPacket(inBuf, upDstEp.socketAddress()));
        } else {
            ByteBuf finalInBuf = inBuf;
            outboundFuture.addListener((ChannelFutureListener) f -> f.channel().writeAndFlush(new DatagramPacket(finalInBuf, upDstEp.socketAddress())));
        }
        if (debug) {
            log.info("SS UDP OUT {} => {}[{}]", srcEp, upDstEp, dstEp);
        }
    }
}
