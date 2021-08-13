package org.rx.net.shadowsocks;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.*;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.extern.slf4j.Slf4j;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.GenericInboundHandler;
import org.rx.net.Sockets;
import org.rx.net.socks.SocksContext;
import org.rx.net.socks.UdpManager;
import org.rx.net.socks.upstream.Upstream;
import org.rx.net.support.UnresolvedEndpoint;

import java.net.InetSocketAddress;

@Slf4j
@ChannelHandler.Sharable
public class ServerUdpProxyHandler extends GenericInboundHandler<ByteBuf> {
    public static final ServerUdpProxyHandler DEFAULT = new ServerUdpProxyHandler();

    @Override
    protected void channelRead0(ChannelHandlerContext inbound, ByteBuf inBuf) throws Exception {
        InetSocketAddress clientSender = inbound.channel().attr(SSCommon.REMOTE_ADDRESS).get();
        InetSocketAddress clientRecipient = inbound.channel().attr(SSCommon.REMOTE_DEST).get();
        ShadowsocksServer server = SocksContext.ssServer(inbound.channel());

        Channel outbound = UdpManager.openChannel(clientSender, k -> {
            Upstream upstream = server.udpRouter.invoke(new UnresolvedEndpoint(clientRecipient));
            return SocksContext.initOutbound(Sockets.udpBootstrap(server.config.getMemoryMode(), ob -> {
                upstream.initChannel(ob);

                ob.pipeline().addLast(new IdleStateHandler(0, 0, server.config.getIdleTimeout()) {
                    @Override
                    protected IdleStateEvent newIdleStateEvent(IdleState state, boolean first) {
                        UdpManager.closeChannel(clientSender);
                        return super.newIdleStateEvent(state, first);
                    }
                }, new GenericInboundHandler<DatagramPacket>() {
                    @Override
                    protected void channelRead0(ChannelHandlerContext outbound, DatagramPacket out) throws Exception {
                        ByteBuf outBuf = out.content();
                        if (upstream.getSocksServer() != null) {
                            UnresolvedEndpoint dstEp = UdpManager.socks5Decode(outBuf);
                            log.info("RECV DECODE {}[{}] => {} {}", out.sender(), dstEp, clientSender, outBuf);
                        }

                        inbound.attr(SSCommon.REMOTE_SRC).set(out.sender());
                        inbound.writeAndFlush(outBuf);
                    }
                });
            }).bind(0).addListener(UdpManager.FLUSH_PENDING_QUEUE).channel(), clientSender, upstream.getDestination(), upstream);
        });

        Upstream upstream = SocksContext.upstream(outbound);
        UnresolvedEndpoint dstEp = upstream.getDestination();
        AuthenticEndpoint svrEp = upstream.getSocksServer();
        if (svrEp != null) {
            inBuf = UdpManager.socks5Encode(inBuf, dstEp);
            dstEp = new UnresolvedEndpoint(svrEp.getEndpoint());
        }
        UdpManager.pendOrWritePacket(outbound, new DatagramPacket(inBuf, dstEp.socketAddress()));
    }
}
