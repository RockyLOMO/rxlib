package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
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
            SocksProxyServer server = Sockets.getAttr(outbound, SocksContext.SOCKS_SVR);
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
        SocksProxyServer server = Sockets.getAttr(inbound, SocksContext.SOCKS_SVR);
        final InetSocketAddress srcEp = in.sender();
        InetAddress saddr = srcEp.getAddress();
        if (!saddr.isLoopbackAddress() && !Sockets.isPrivateIp(saddr)
                && !server.config.getWhiteList().contains(saddr)) {
            log.warn("UDP security error, package from {}", srcEp);
            return;
        }
        final UnresolvedEndpoint dstEp = UdpManager.socks5Decode(inBuf);

        Channel outbound = UdpManager.openChannel(srcEp, k -> {
            SocksContext e = new SocksContext(srcEp, dstEp);
            server.raiseEvent(server.onUdpRoute, e);
            Upstream upstream = e.getUpstream();

            Channel ch = Sockets.udpBootstrap(server.config.getMemoryMode(), ob -> {
                        upstream.initChannel(ob);
                        ob.pipeline().addLast(new ProxyChannelIdleHandler(server.config.getUdpReadTimeoutSeconds(), server.config.getUdpWriteTimeoutSeconds()),
                                UdpBackendRelayHandler.DEFAULT);
                    }).attr(SocksContext.SOCKS_SVR, server).bind(0).addListener(Sockets.logBind(0))
//                    .syncUninterruptibly()
                    .channel();
            SocksContext.mark(inbound, ch, e, false);
            log.debug("socks5[{}] UDP open {}", server.config.getListenPort(), srcEp);
            ch.closeFuture().addListener(f -> {
                log.debug("socks5[{}] UDP close {}", server.config.getListenPort(), srcEp);
                UdpManager.closeChannel(srcEp);
            });
            return ch;
        });

        SocksContext sc = SocksContext.ctx(outbound);
        //udp dstEp可能多个，但upstream.getDestination()只有一个，所以直接用dstEp。
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
