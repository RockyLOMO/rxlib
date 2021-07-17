package org.rx.net.socks;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.proxy.ProxyConnectException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.rx.net.Sockets;

import java.util.Collection;

@Slf4j
@RequiredArgsConstructor
public class ForwardingBackendHandler extends ChannelInboundHandlerAdapter {
    public static final String PIPELINE_NAME = "from-upstream";
    final ChannelHandlerContext inbound;
    final Collection<Object> outboundPendingPackages;

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        if (CollectionUtils.isEmpty(outboundPendingPackages)) {
            return;
        }

        Channel outbound = ctx.channel();
        log.debug("{} flush forwarded to {} => {}", inbound.channel().remoteAddress(), outbound.localAddress(), outbound.remoteAddress());
        Sockets.writeAndFlush(outbound, outboundPendingPackages);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!inbound.channel().isActive()) {
            return;
        }

        Channel outbound = ctx.channel();
        log.debug("{} => {} forwarded to {}", outbound.remoteAddress(), outbound.localAddress(), inbound.channel().remoteAddress());
        inbound.writeAndFlush(msg).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        Sockets.closeOnFlushed(inbound.channel());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        Channel outbound = ctx.channel();
        if (cause instanceof ProxyConnectException) {
            log.warn("{} => {} forwarded to {} thrown\n{}", outbound.remoteAddress(), outbound.localAddress(), inbound.channel().remoteAddress(), cause.getMessage());
        } else {
            log.warn("{} => {} forwarded to {} thrown", outbound.remoteAddress(), outbound.localAddress(), inbound.channel().remoteAddress(), cause);
        }
        Sockets.closeOnFlushed(outbound);
    }
}
