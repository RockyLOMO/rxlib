package org.rx.net.socks;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rx.net.Sockets;

import java.util.Collection;

@Slf4j
@RequiredArgsConstructor
public class ForwardingFrontendHandler extends ChannelInboundHandlerAdapter {
    public static final String PIPELINE_NAME = "to-upstream";
    final Channel outbound;
    final Collection<Object> pendingPackages;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        Channel inbound = ctx.channel();
        if (!outbound.isActive()) {
            if (pendingPackages != null) {
                log.debug("{} pending forwarded to {} => {}", outbound.remoteAddress(), outbound.localAddress(), inbound.remoteAddress());
                pendingPackages.add(msg);
            }
            return;
        }

        log.debug("{} forwarded to {} => {}", inbound.remoteAddress(), outbound.localAddress(), outbound.remoteAddress());
        outbound.writeAndFlush(msg).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        Sockets.closeOnFlushed(outbound);
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        Channel inbound = ctx.channel();
        log.warn("{} forwarded to {} => {} thrown", inbound.remoteAddress(), outbound.localAddress(), outbound.remoteAddress(), cause);
        Sockets.closeOnFlushed(inbound);
    }
}
