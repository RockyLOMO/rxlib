package org.rx.net.shadowsocks;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.socket.DatagramPacket;
import lombok.extern.slf4j.Slf4j;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.Sockets;
import org.rx.net.socks.*;
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
            InetSocketAddress srcEp = sc.getSource();
            UnresolvedEndpoint dstEp = sc.getFirstDestination();
            ByteBuf outBuf = out.content();
            if (sc.getUpstream().getSocksServer() != null) {
                UnresolvedEndpoint tmp = UdpManager.socks5Decode(outBuf);
                if (!dstEp.equals(tmp)) {
                    log.error("UDP SOCKS ERROR {} != {}", dstEp, tmp);
                }
                sc.inbound.attr(SSCommon.REMOTE_SRC).set(tmp.socketAddress());
            } else {
                sc.inbound.attr(SSCommon.REMOTE_SRC).set(out.sender());
            }

            sc.inbound.writeAndFlush(outBuf.retain());
            log.info("UDP IN {}[{}] => {}", out.sender(), dstEp, srcEp);
//            log.info("UDP IN {}[{}] => {}\n{}", out.sender(), dstEp, srcEp, Bytes.hexDump(outBuf));
        }
    }

    public static final ServerUdpProxyHandler DEFAULT = new ServerUdpProxyHandler();

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf inBuf) throws Exception {
        Channel inbound = ctx.channel();
        InetSocketAddress srcEp = inbound.attr(SSCommon.REMOTE_ADDRESS).get();
        UnresolvedEndpoint dstEp = new UnresolvedEndpoint(inbound.attr(SSCommon.REMOTE_DEST).get());
        ShadowsocksServer server = SocksContext.ssServer(inbound, true);

        Channel outbound = UdpManager.openChannel(srcEp, k -> {
            SocksContext e = new SocksContext(srcEp, dstEp);
            server.raiseEvent(server.onUdpRoute, e);
            Upstream upstream = e.getUpstream();

            return Sockets.udpBootstrap(server.config.getMemoryMode(), ob -> {
                        SocksContext.ssServer(ob, server);
                        SocksContext.mark(inbound, ob, e, false);
                        e.onClose = () -> UdpManager.closeChannel(srcEp);

                        upstream.initChannel(ob);
                        ob.pipeline().addLast(new ProxyChannelIdleHandler(server.config.getIdleTimeout(), 0), UdpBackendRelayHandler.DEFAULT);
                    }).bind(0)
//                    .addListener(UdpManager.FLUSH_PENDING_QUEUE).channel();
                    .syncUninterruptibly().channel();
        });

        SocksContext sc = SocksContext.ctx(outbound);
        UnresolvedEndpoint upDstEp = sc.getUpstream().getDestination();
        AuthenticEndpoint upSvrEp = sc.getUpstream().getSocksServer();
        if (upSvrEp != null) {
            inBuf = UdpManager.socks5Encode(inBuf, dstEp);
            upDstEp = new UnresolvedEndpoint(upSvrEp.getEndpoint());
        } else {
            inBuf.retain();
        }
        UdpManager.pendOrWritePacket(outbound, new DatagramPacket(inBuf, upDstEp.socketAddress()));
        log.info("UDP OUT {} => {}[{}]", srcEp, upDstEp, dstEp);
    }
}
