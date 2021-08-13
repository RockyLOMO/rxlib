package org.rx.net.shadowsocks;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.extern.slf4j.Slf4j;
import org.rx.net.Sockets;
import org.rx.net.socks.SocksContext;
import org.rx.net.socks.UdpManager;
import org.rx.net.socks.upstream.Upstream;
import org.rx.net.support.UnresolvedEndpoint;

import java.net.InetSocketAddress;

@Slf4j
@ChannelHandler.Sharable
public class ServerUdpProxyHandler extends SimpleChannelInboundHandler<ByteBuf> {
    public static final ServerUdpProxyHandler DEFAULT = new ServerUdpProxyHandler();

    @Override
    protected void channelRead0(ChannelHandlerContext inbound, ByteBuf msg) throws Exception {
        InetSocketAddress clientSender = inbound.channel().attr(SSCommon.REMOTE_ADDRESS).get();
        InetSocketAddress clientRecipient = inbound.channel().attr(SSCommon.REMOTE_DEST).get();
        ShadowsocksServer server = SocksContext.ssServer(inbound.channel());

        UdpManager.UdpChannelUpstream outCtx = UdpManager.openChannel(clientSender, k -> {
            Upstream upstream = server.udpRouter.invoke(new UnresolvedEndpoint(clientRecipient));
            Channel channel = Sockets.udpBootstrap(server.config.getMemoryMode(), outbound -> {
                upstream.initChannel(outbound);

                outbound.pipeline().addLast(new IdleStateHandler(0, 0, server.config.getIdleTimeout()) {
                    @Override
                    protected IdleStateEvent newIdleStateEvent(IdleState state, boolean first) {
                        UdpManager.closeChannel(clientSender);
                        return super.newIdleStateEvent(state, first);
                    }
                }, new SimpleChannelInboundHandler<DatagramPacket>() {
                    @Override
                    protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket msg) throws Exception {
                        inbound.attr(SSCommon.REMOTE_SRC).set(msg.sender());
                        inbound.writeAndFlush(msg.content().retain());
                    }
                });
            }).bind(0).addListener(UdpManager.FLUSH_PENDING_QUEUE).channel();
            SocksContext.initPendingQueue(channel, clientSender, new UnresolvedEndpoint(clientRecipient));
            return new UdpManager.UdpChannelUpstream(channel, upstream);
        });

        UdpManager.pendOrWritePacket(outCtx.getChannel(), new DatagramPacket(msg.retain(), clientRecipient));
    }
}
