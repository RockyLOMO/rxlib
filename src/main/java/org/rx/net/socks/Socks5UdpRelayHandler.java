package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.extern.slf4j.Slf4j;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.Sockets;
import org.rx.net.socks.upstream.Upstream;
import org.rx.net.support.UnresolvedEndpoint;

import java.net.InetSocketAddress;

@Slf4j
@ChannelHandler.Sharable
public class Socks5UdpRelayHandler extends SimpleChannelInboundHandler<DatagramPacket> {
    public static final Socks5UdpRelayHandler DEFAULT = new Socks5UdpRelayHandler();

    /**
     * https://datatracker.ietf.org/doc/html/rfc1928
     * +----+------+------+----------+----------+----------+
     * |RSV | FRAG | ATYP | DST.ADDR | DST.PORT |   DATA   |
     * +----+------+------+----------+----------+----------+
     * | 2  |  1   |  1   | Variable |    2     | Variable |
     * +----+------+------+----------+----------+----------+
     *
     * @param inbound
     * @param in
     * @throws Exception
     */
    @Override
    protected void channelRead0(ChannelHandlerContext inbound, DatagramPacket in) throws Exception {
        ByteBuf inBuf = in.content();
        if (inBuf.readableBytes() < 4) {
            return;
        }

        SocksProxyServer server = SocksContext.server(inbound.channel());
        final InetSocketAddress srcEp = in.sender();
        if (!server.config.getWhiteList().contains(srcEp.getAddress())) {
            log.warn("security error, package from {}", srcEp);
            return;
        }
        final UnresolvedEndpoint dstEp = UdpManager.socks5Decode(inBuf);

        Channel outbound = UdpManager.openChannel(srcEp, k -> {
            RouteEventArgs e = new RouteEventArgs(srcEp, dstEp);
            server.raiseEvent(server.onUdpRoute, e);
            Upstream upstream = e.getValue();
            return SocksContext.initOutbound(Sockets.udpBootstrap(server.config.getMemoryMode(), ob -> {
                SocksContext.server(ob, server);
                upstream.initChannel(ob);

                ob.pipeline().addLast(new IdleStateHandler(0, 0, server.config.getUdpTimeoutSeconds()) {
                    @Override
                    protected IdleStateEvent newIdleStateEvent(IdleState state, boolean first) {
                        UdpManager.closeChannel(SocksContext.realSource(ob));
                        return super.newIdleStateEvent(state, first);
                    }
                }, new SimpleChannelInboundHandler<DatagramPacket>() {
                    @Override
                    protected void channelRead0(ChannelHandlerContext outbound, DatagramPacket out) throws Exception {
                        InetSocketAddress srcEp = SocksContext.realSource(outbound.channel());
                        UnresolvedEndpoint dstEp = SocksContext.realDestination(outbound.channel());
                        ByteBuf outBuf = out.content();
                        if (upstream.getSocksServer() == null) {
                            outBuf = UdpManager.socks5Encode(outBuf, dstEp);
                        } else {
                            outBuf.retain();
                        }
                        inbound.writeAndFlush(new DatagramPacket(outBuf, srcEp));
                        log.debug("socks5[{}] UDP IN {}[{}] => {}", server.config.getListenPort(), out.sender(), dstEp, srcEp);
                    }
                });
            }).bind(0).addListener(Sockets.logBind(0))
                    //pendingQueue模式flush时需要等待一会(1000ms)才能发送，故先用sync()方式。
//                    .addListener(UdpManager.FLUSH_PENDING_QUEUE).channel(), srcEp, dstEp, upstream)
                    .sync().channel(), srcEp, dstEp, upstream, false);
        });

        Upstream upstream = SocksContext.upstream(outbound);
//        UnresolvedEndpoint upDstEp = upstream.getDestination();  //udp dstEp可能多个，但upstream只有一个，所以直接用dstEp。
        UnresolvedEndpoint upDstEp = dstEp;
        AuthenticEndpoint socksServer = upstream.getSocksServer();
        if (socksServer != null) {
            upDstEp = new UnresolvedEndpoint(socksServer.getEndpoint());
            inBuf.readerIndex(0);
        }
        UdpManager.pendOrWritePacket(outbound, new DatagramPacket(inBuf.retain(), upDstEp.socketAddress()));
        log.debug("socks5[{}] UDP OUT {} => {}[{}]", server.config.getListenPort(), srcEp, upDstEp, dstEp);
    }
}
