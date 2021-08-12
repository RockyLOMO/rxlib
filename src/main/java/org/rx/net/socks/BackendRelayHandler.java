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
public class BackendRelayHandler extends ChannelInboundHandlerAdapter {
    public static final String PIPELINE_NAME = "from-upstream";
    final Channel inbound;
    final Collection<Object> outboundPendingPackages;

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        if (CollectionUtils.isEmpty(outboundPendingPackages)) {
            return;
        }

        Channel outbound = ctx.channel();
        log.info("RELAY {} => {}[{}] flush packets", inbound.remoteAddress(), outbound.localAddress(), outbound.remoteAddress());
        Sockets.writeAndFlush(outbound, outboundPendingPackages);
        super.channelActive(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        Channel outbound = ctx.channel();
        log.debug("RELAY {}[{}] => {}", outbound.remoteAddress(), outbound.localAddress(), inbound.remoteAddress());
        inbound.writeAndFlush(msg).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        Sockets.closeOnFlushed(inbound);
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        Channel outbound = ctx.channel();
        if (cause instanceof ProxyConnectException) {
            log.warn("RELAY {}[{}] => {} thrown\n{}", outbound.remoteAddress(), outbound.localAddress(), inbound.remoteAddress(), cause.getMessage());
        } else {
            log.warn("RELAY {}[{}] => {} thrown", outbound.remoteAddress(), outbound.localAddress(), inbound.remoteAddress(), cause);
        }
        Sockets.closeOnFlushed(outbound);
    }
}
