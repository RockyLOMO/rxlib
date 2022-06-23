package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.socket.DatagramPacket;
import lombok.extern.slf4j.Slf4j;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.Sockets;
import org.rx.net.socks.upstream.Upstream;
import org.rx.net.support.UnresolvedEndpoint;

import java.net.InetSocketAddress;

@Slf4j
@ChannelHandler.Sharable
public class Socks5UdpRelayHandler extends SimpleChannelInboundHandler<DatagramPacket> {
    @Slf4j
    @ChannelHandler.Sharable
    public static class UdpBackendRelayHandler extends SimpleChannelInboundHandler<DatagramPacket> {
        public static final UdpBackendRelayHandler DEFAULT = new UdpBackendRelayHandler();

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket out) throws Exception {
            Channel outbound = ctx.channel();
            SocksContext sc = SocksContext.ctx(outbound);
            InetSocketAddress srcEp = sc.source;
            UnresolvedEndpoint dstEp = sc.firstDestination;
            ByteBuf outBuf = out.content();
            SocksProxyServer server = SocksContext.server(outbound);
            if (sc.upstream.getSocksServer() == null) {
//                log.debug("socks5[{}] UDP IN {}", server.config.getListenPort(), Bytes.hexDump(outBuf.retain()));
                outBuf = UdpManager.socks5Encode(outBuf, dstEp);
            } else {
                outBuf.retain();
            }
            sc.inbound.writeAndFlush(new DatagramPacket(outBuf, srcEp));

            log.debug("socks5[{}] UDP IN {}[{}] => {}", server.config.getListenPort(), out.sender(), dstEp, srcEp);
//            log.debug("socks5[{}] UDP IN {}[{}] => {}\n{}", server.config.getListenPort(), out.sender(), dstEp, srcEp, Bytes.hexDump(outBuf.retain()));
        }
    }

    public static final Socks5UdpRelayHandler DEFAULT = new Socks5UdpRelayHandler();

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
        SocksProxyServer server = SocksContext.server(inbound);
        final InetSocketAddress srcEp = in.sender();
        if (!Sockets.isNatIp(srcEp.getAddress()) && !server.config.getWhiteList().contains(srcEp.getAddress())) {
            log.warn("security error, package from {}", srcEp);
            return;
        }
        final UnresolvedEndpoint dstEp = UdpManager.socks5Decode(inBuf);

        Channel outbound = UdpManager.openChannel(srcEp, k -> {
            SocksContext e = new SocksContext(srcEp, dstEp);
            server.raiseEvent(server.onUdpRoute, e);
            Upstream upstream = e.getUpstream();

            return Sockets.udpBootstrap(server.config.getMemoryMode(), ob -> {
                SocksContext.server(ob, server);
                SocksContext.mark(inbound, ob, e, false);
                e.onClose = () -> UdpManager.closeChannel(srcEp);

                upstream.initChannel(ob);
                ob.pipeline().addLast(new ProxyChannelIdleHandler(server.config.getUdpReadTimeoutSeconds(), server.config.getUdpWriteTimeoutSeconds()),
                        UdpBackendRelayHandler.DEFAULT);
            }).bind(0).addListener(Sockets.logBind(0)).sync().channel();

//            Channel ob2 = Sockets.udpBootstrap(server.config.getMemoryMode(), ob -> {
//                SocksContext.server(ob, server);
//                e.onClose = () -> UdpManager.closeChannel(srcEp);
//
//                upstream.initChannel(ob);
//                ob.pipeline().addLast(new ProxyChannelIdleHandler(server.config.getUdpReadTimeoutSeconds(), server.config.getUdpWriteTimeoutSeconds()),
//                        UdpBackendRelayHandler.DEFAULT);
//            }).bind(0).addListener(Sockets.logBind(0))
//                    //pendingQueue模式flush时需要等待一会(1000ms)才能发送，故先用sync()方式。
//                    .addListener(UdpManager.FLUSH_PENDING_QUEUE).channel();
//            SocksContext.mark(inbound, ob2, e, true);
//            return ob2;
        });
        //todo sync改eventcallback

        SocksContext sc = SocksContext.ctx(outbound);
//        UnresolvedEndpoint upDstEp = upstream.getDestination();  //udp dstEp可能多个，但upstream只有一个，所以直接用dstEp。
        UnresolvedEndpoint upDstEp = dstEp;
        AuthenticEndpoint upSvrEp = sc.getUpstream().getSocksServer();
        if (upSvrEp != null) {
            upDstEp = new UnresolvedEndpoint(upSvrEp.getEndpoint());
            inBuf.readerIndex(0);
        }
        UdpManager.pendOrWritePacket(outbound, new DatagramPacket(inBuf.retain(), upDstEp.socketAddress()));
        log.debug("socks5[{}] UDP OUT {} => {}[{}]", server.config.getListenPort(), srcEp, upDstEp, dstEp);
    }
}
