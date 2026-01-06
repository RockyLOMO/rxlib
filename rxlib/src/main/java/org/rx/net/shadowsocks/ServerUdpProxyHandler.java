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
public class ServerUdpProxyHandler extends SimpleChannelInboundHandler<ByteBuf> {
    @Slf4j
    @ChannelHandler.Sharable
    public static class UdpBackendRelayHandler extends SimpleChannelInboundHandler<DatagramPacket> {
        public static final UdpBackendRelayHandler DEFAULT = new UdpBackendRelayHandler();

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket out) throws Exception {
            Channel outbound = ctx.channel();
            SocksContext sc = SocksContext.ctx(outbound);
            UnresolvedEndpoint dstEp = sc.getFirstDestination();
            ByteBuf outBuf = out.content();
            if (sc.getUpstream().getUdpSocksServer() != null) {
                UnresolvedEndpoint tmp = UdpManager.socks5Decode(outBuf);
                if (!dstEp.equals(tmp)) {
                    log.error("UDP error dstEp not matched {} != {}", dstEp, tmp);
                }
                sc.inbound.attr(SSCommon.REMOTE_SRC).set(tmp.socketAddress());
            } else {
                sc.inbound.attr(SSCommon.REMOTE_SRC).set(out.sender());
            }

            sc.inbound.writeAndFlush(outBuf.retain());
//            log.info("UDP IN {}[{}] => {}", out.sender(), dstEp, srcEp);
//            log.info("UDP IN {}[{}] => {}\n{}", out.sender(), dstEp, srcEp, Bytes.hexDump(outBuf));
        }
    }

    public static final ServerUdpProxyHandler DEFAULT = new ServerUdpProxyHandler();

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf inBuf) throws Exception {
        Channel inbound = ctx.channel();
        InetSocketAddress srcEp = inbound.attr(SSCommon.REMOTE_ADDRESS).get();
        UnresolvedEndpoint dstEp = new UnresolvedEndpoint(inbound.attr(SSCommon.REMOTE_DEST).get());
        ShadowsocksServer server = Sockets.getAttr(inbound, SocksContext.SS_SVR);

        ChannelFuture outboundFuture = UdpManager.open(srcEp, k -> {
            SocksContext e = new SocksContext(srcEp, dstEp);
            server.raiseEvent(server.onUdpRoute, e);
            Upstream upstream = e.getUpstream();
            ChannelFuture chf = Sockets.udpBootstrap(upstream.getConfig(), ob -> {
                upstream.initChannel(ob);
                ob.pipeline().addLast(new ProxyChannelIdleHandler(server.config.getUdpTimeoutSeconds(), 0),
                        UdpBackendRelayHandler.DEFAULT);
            }).attr(SocksContext.SS_SVR, server).bind(0);
            SocksContext.mark(inbound, chf, e);
            chf.channel().closeFuture().addListener(f -> UdpManager.close(srcEp));
            return chf;
        });
        Channel outbound = outboundFuture.channel();

        SocksContext sc = SocksContext.ctx(outbound);
        UnresolvedEndpoint upDstEp;
        AuthenticEndpoint upSvrEp = sc.getUpstream().getUdpSocksServer();
        if (upSvrEp != null) {
            inBuf = UdpManager.socks5Encode(inBuf, dstEp);
            upDstEp = new UnresolvedEndpoint(upSvrEp.getEndpoint());
        } else {
            inBuf.retain();
            upDstEp = sc.getUpstream().getDestination();
        }
        if (sc.isOutboundActive()) {
            outbound.writeAndFlush(new DatagramPacket(inBuf, upDstEp.socketAddress()));
        } else {
            ByteBuf finalInBuf = inBuf;
            outboundFuture.addListener((ChannelFutureListener) f -> f.channel().writeAndFlush(new DatagramPacket(finalInBuf, upDstEp.socketAddress())));
        }
//        log.info("UDP OUT {} => {}[{}]", srcEp, upDstEp, dstEp);
    }
}
