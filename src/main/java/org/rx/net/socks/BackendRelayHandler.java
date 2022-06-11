package org.rx.net.socks;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.proxy.ProxyConnectException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.rx.net.Sockets;

@Slf4j
@ChannelHandler.Sharable
public class BackendRelayHandler extends ChannelInboundHandlerAdapter {
    public static final BackendRelayHandler DEFAULT = new BackendRelayHandler();

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        Channel outbound = ctx.channel();
        SocksContext sc = SocksContext.ctx(outbound);
        if (CollectionUtils.isEmpty(sc.pendingPackages)) {
            return;
        }

        log.debug("RELAY {} => {}[{}] flush packets", sc.inbound.remoteAddress(), outbound.localAddress(), outbound.remoteAddress());
        Sockets.writeAndFlush(outbound, sc.pendingPackages);
        super.channelActive(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        Channel outbound = ctx.channel();
        SocksContext sc = SocksContext.ctx(outbound);
        log.debug("RELAY {}[{}] => {}", outbound.remoteAddress(), outbound.localAddress(), sc.inbound.remoteAddress());
        sc.inbound.writeAndFlush(msg);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        SocksContext sc = SocksContext.ctx(ctx.channel());
        Sockets.closeOnFlushed(sc.inbound);
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        Channel outbound = ctx.channel();
        SocksContext sc = SocksContext.ctx(outbound);
        if (cause instanceof ProxyConnectException) {
            log.warn("RELAY {}[{}] => {} thrown\n{}", outbound.remoteAddress(), outbound.localAddress(), sc.inbound.remoteAddress(), cause.getMessage());
        } else {
            log.warn("RELAY {}[{}] => {} thrown", outbound.remoteAddress(), outbound.localAddress(), sc.inbound.remoteAddress(), cause);
        }
        Sockets.closeOnFlushed(outbound);
    }
}
