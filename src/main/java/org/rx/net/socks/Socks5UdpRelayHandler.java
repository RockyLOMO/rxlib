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
import org.rx.net.GenericInboundHandler;
import org.rx.net.Sockets;
import org.rx.net.socks.upstream.Upstream;
import org.rx.net.support.UnresolvedEndpoint;

import java.net.InetSocketAddress;

@Slf4j
@ChannelHandler.Sharable
public class Socks5UdpRelayHandler extends GenericInboundHandler<DatagramPacket> {
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

        UdpManager.UdpChannelUpstream outCtx = UdpManager.openChannel(sourceEp, k -> {
            RouteEventArgs e = new RouteEventArgs(sourceEp, destinationEp);
            server.raiseEvent(server.onUdpRoute, e);
            Channel channel = Sockets.udpBootstrap(server.config.getMemoryMode(), outbound -> {
                SocksContext.server(outbound, server);
                e.getValue().initChannel(outbound);

                outbound.pipeline().addLast(new IdleStateHandler(0, 0, server.config.getUdpTimeoutSeconds()) {
                    @Override
                    protected IdleStateEvent newIdleStateEvent(IdleState state, boolean first) {
                        UdpManager.closeChannel(SocksContext.udpSource(outbound));
                        return super.newIdleStateEvent(state, first);
                    }
                }, new GenericInboundHandler<DatagramPacket>() {
                    @Override
                    protected void channelRead0(ChannelHandlerContext outbound, DatagramPacket out) throws Exception {
                        ByteBuf outBuf;
                        if (e.getValue().getSocksServer() != null) {
                            outBuf = out.content();
                        } else {
                            outBuf = UdpManager.socks5Encode(out.content(), e.getDestinationEndpoint());
                        }
                        inbound.writeAndFlush(new DatagramPacket(outBuf, e.getSourceEndpoint()));
                        log.info("socks5[{}] UDP IN {}[{}] => {}", out.recipient().getPort(), out.sender(), e.getDestinationEndpoint(), e.getSourceEndpoint());
                    }
                });
            }).bind(0).addListener(Sockets.logBind(0)).addListener(UdpManager.FLUSH_PENDING_QUEUE).channel();
            SocksContext.initPendingQueue(channel, e.getSourceEndpoint(), e.getDestinationEndpoint());
            return new UdpManager.UdpChannelUpstream(channel, e.getValue());
        });

        UnresolvedEndpoint upDstEp = outCtx.getUpstream().getDestination();
        AuthenticEndpoint svrEp = outCtx.getUpstream().getSocksServer();
        if (svrEp != null) {
            upDstEp = new UnresolvedEndpoint(svrEp.getEndpoint());
            inBuf.readerIndex(0);
        }
        DatagramPacket packet = new DatagramPacket(inBuf, upDstEp.socketAddress());
        UdpManager.pendOrWritePacket(outCtx.getChannel(), packet);
        log.info("socks5[{}] UDP OUT {} => {}[{}]", in.recipient().getPort(), sourceEp, upDstEp, destinationEp);
    }
}
