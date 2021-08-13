package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.GenericChannelInboundHandler;
import org.rx.net.Sockets;
import org.rx.net.socks.upstream.Upstream;
import org.rx.net.support.UnresolvedEndpoint;

import java.net.InetSocketAddress;

@Slf4j
@ChannelHandler.Sharable
public class Socks5UdpRelayHandler extends GenericChannelInboundHandler<DatagramPacket> {
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
    @SneakyThrows
    @Override
    protected void channelRead0(ChannelHandlerContext inbound, DatagramPacket in) throws Exception {
        ByteBuf inBuf = in.content();
        if (inBuf.readableBytes() < 4) {
            return;
        }

        SocksProxyServer server = SocksContext.server(inbound.channel());
        InetSocketAddress sourceEp = in.sender();
        UnresolvedEndpoint destinationEp = UdpManager.socks5Decode(inBuf);

        UnresolvedEndpoint finalDestinationEp = destinationEp;
        UdpManager.UdpChannelUpstream outCtx = UdpManager.openChannel(sourceEp, k -> {
            RouteEventArgs e = new RouteEventArgs(sourceEp, finalDestinationEp);
            server.raiseEvent(server.onUdpRoute, e);
            Upstream upstream = e.getValue();
            Channel channel = Sockets.udpBootstrap(server.config.getMemoryMode(), outbound -> {
                SocksContext.server(outbound, server);
                upstream.initChannel(outbound);

                outbound.pipeline().addLast(new IdleStateHandler(0, 0, server.config.getUdpTimeoutSeconds()) {
                    @Override
                    protected IdleStateEvent newIdleStateEvent(IdleState state, boolean first) {
                        UdpManager.closeChannel(SocksContext.udpSource(outbound));
                        return super.newIdleStateEvent(state, first);
                    }
                }, new GenericChannelInboundHandler<DatagramPacket>() {
                    @Override
                    protected void channelRead0(ChannelHandlerContext outbound, DatagramPacket out) throws Exception {
                        ByteBuf outBuf;
                        if (upstream.getSocksServer() != null) {
                            outBuf = out.content();
                        } else {
                            UnresolvedEndpoint destinationEp = SocksContext.realDestination(outbound.channel());
                            outBuf = UdpManager.socks5Encode(out.content(), destinationEp);
                        }
                        inbound.writeAndFlush(new DatagramPacket(outBuf, sourceEp));
                        log.info("UDP[{}] IN {} => {}", out.recipient(), out.sender(), sourceEp);
                    }
                });
            }).bind(0).addListener(Sockets.logBind(0)).addListener(UdpManager.FLUSH_PENDING_QUEUE).channel();
            SocksContext.initPendingQueue(channel, sourceEp, finalDestinationEp);
            return new UdpManager.UdpChannelUpstream(channel, upstream);
        });

        AuthenticEndpoint svrEp = outCtx.getUpstream().getSocksServer();
        if (svrEp != null) {
            destinationEp = new UnresolvedEndpoint(svrEp.getEndpoint());
            inBuf.readerIndex(0);
        }
        DatagramPacket packet = new DatagramPacket(inBuf, destinationEp.socketAddress());

        Channel outbound = outCtx.getChannel();
        UdpManager.pendOrWritePacket(outbound, packet);
        log.info("UDP[{}] OUT {} => {}", in.recipient(), sourceEp, destinationEp);
    }
}
