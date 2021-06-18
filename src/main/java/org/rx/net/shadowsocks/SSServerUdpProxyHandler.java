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
public class SSServerUdpProxyHandler extends SimpleChannelInboundHandler<ByteBuf> {
    @Override
    protected void channelRead0(ChannelHandlerContext clientCtx, ByteBuf msg) throws Exception {
        InetSocketAddress clientSender = clientCtx.channel().attr(SSCommon.REMOTE_ADDRESS).get();
        InetSocketAddress clientRecipient = clientCtx.channel().attr(SSCommon.REMOTE_DEST).get();
        proxy(clientSender, clientRecipient, clientCtx, msg.retain());
    }

    @SneakyThrows
    private void proxy(InetSocketAddress clientSender, InetSocketAddress clientRecipient, ChannelHandlerContext clientCtx, ByteBuf msg) {
        Channel udpChannel = NatMapper.getUdpChannel(clientSender);
        if (udpChannel == null) {
            udpChannel = Sockets.udpBootstrap()
                    .option(ChannelOption.SO_RCVBUF, 64 * 1024)// 设置UDP读缓冲区为64k
                    .option(ChannelOption.SO_SNDBUF, 64 * 1024)// 设置UDP写缓冲区为64k
                    .handler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel ch) throws Exception {
                            int proxyIdleTimeout = clientRecipient.getPort() != 53
                                    ? SSCommon.UDP_PROXY_IDLE_TIME
                                    : SSCommon.UDP_DNS_PROXY_IDLE_TIME;
                            ch.pipeline().addLast(new IdleStateHandler(0, 0, proxyIdleTimeout, TimeUnit.SECONDS) {
                                @Override
                                protected IdleStateEvent newIdleStateEvent(IdleState state, boolean first) {
                                    NatMapper.closeUdpChannel(clientSender);
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
                            NatMapper.putUdpChannel(clientSender, future.channel());
                        }
                    }).sync().channel();
        }

        if (udpChannel != null) {
            udpChannel.writeAndFlush(new DatagramPacket(msg, clientRecipient));
        }
    }
}
