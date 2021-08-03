package org.rx.net.shadowsocks;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.net.Sockets;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

@Slf4j
@ChannelHandler.Sharable
public class ServerUdpProxyHandler extends SimpleChannelInboundHandler<ByteBuf> {
    public static final ServerUdpProxyHandler DEFAULT = new ServerUdpProxyHandler();

    @Override
    protected void channelRead0(ChannelHandlerContext clientCtx, ByteBuf msg) throws Exception {
        InetSocketAddress clientSender = clientCtx.channel().attr(SSCommon.REMOTE_ADDRESS).get();
        InetSocketAddress clientRecipient = clientCtx.channel().attr(SSCommon.REMOTE_DEST).get();
        proxy(clientSender, clientRecipient, clientCtx, msg.retain());
    }

    @SneakyThrows
    private void proxy(InetSocketAddress clientSender, InetSocketAddress clientRecipient, ChannelHandlerContext clientCtx, ByteBuf msg) {
        Channel udpChannel = NatMapper.getChannel(clientSender, k -> Sockets.udpBootstrap().handler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel ch) throws Exception {
                ch.pipeline().addLast(new IdleStateHandler(0, 0, SSCommon.UDP_PROXY_IDLE_TIME, TimeUnit.SECONDS) {
                    @Override
                    protected IdleStateEvent newIdleStateEvent(IdleState state, boolean first) {
                        NatMapper.closeChannel(clientSender);
                        return super.newIdleStateEvent(state, first);
                    }
                }, new SimpleChannelInboundHandler<DatagramPacket>() {
                    @Override
                    protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket msg) throws Exception {
                        clientCtx.channel().attr(SSCommon.REMOTE_SRC).set(msg.sender());
                        clientCtx.channel().writeAndFlush(msg.retain().content());
                    }
                });
            }
        }).bind(0).addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                log.debug("channel id {}, {}<->{}<->{} connect {}", clientCtx.channel().id().toString(), clientSender.toString(), future.channel().localAddress().toString(), clientRecipient.toString(), future.isSuccess());
            }
        }).sync().channel());

        udpChannel.writeAndFlush(new DatagramPacket(msg, clientRecipient));
    }
}
