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
public class ForwardingFrontendHandler extends ChannelInboundHandlerAdapter {
    private final Channel outbound;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!outbound.isActive()) {
            return;
        }

        Channel inbound = ctx.channel();
        log.trace(String.format("%s forwarded to %s -> %s",
                inbound.remoteAddress(),
                outbound.localAddress(), outbound.remoteAddress()));
        outbound.writeAndFlush(msg).addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        Sockets.closeOnFlushed(outbound);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        Channel inbound = ctx.channel();
        log.warn(String.format("channel %s <-> %s thrown", inbound.localAddress(), inbound.remoteAddress()), cause);
        Sockets.closeOnFlushed(outbound);
    }
}
