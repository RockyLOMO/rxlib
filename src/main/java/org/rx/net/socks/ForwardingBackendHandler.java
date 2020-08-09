package org.rx.net.socks;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rx.net.Sockets;

@Slf4j
@RequiredArgsConstructor
public class ForwardingBackendHandler extends ChannelInboundHandlerAdapter {
    private final ChannelHandlerContext inbound;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!inbound.channel().isActive()) {
            return;
        }

        Channel outbound = ctx.channel();
        log.trace("{} -> {} forwarded to {}", outbound.remoteAddress(), outbound.localAddress(), inbound.channel().remoteAddress());
        inbound.writeAndFlush(msg).addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        Sockets.closeOnFlushed(inbound.channel());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        Channel outbound = ctx.channel();
        log.warn("{} -> {} forwarded to {} thrown", outbound.remoteAddress(), outbound.localAddress(), inbound.channel().remoteAddress(), cause);
        Sockets.closeOnFlushed(outbound);
    }
}
