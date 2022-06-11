package org.rx.net.socks;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.extern.slf4j.Slf4j;
import org.rx.net.Sockets;

@Slf4j
@ChannelHandler.Sharable
public class FrontendRelayHandler extends ChannelInboundHandlerAdapter {
    public static final FrontendRelayHandler DEFAULT = new FrontendRelayHandler();

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        Channel inbound = ctx.channel();
        SocksContext sc = SocksContext.ctx(inbound);
        if (!sc.outbound.isActive()) {
            if (sc.pendingPackages != null) {
                log.debug("PENDING_QUEUE {} => {} pend a packet", inbound.remoteAddress(), sc.outbound);
                sc.pendingPackages.add(msg);
            }
            return;
        }

        log.debug("RELAY {} => {}[{}]", inbound.remoteAddress(), sc.outbound.localAddress(), sc.outbound.remoteAddress());
        sc.outbound.writeAndFlush(msg);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        Channel inbound = ctx.channel();
        SocksContext sc = SocksContext.ctx(inbound);
        Sockets.closeOnFlushed(sc.outbound);
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        Channel inbound = ctx.channel();
        SocksContext sc = SocksContext.ctx(inbound);
        log.warn("RELAY {} => {}[{}] thrown", inbound.remoteAddress(), sc.outbound.localAddress(), sc.outbound.remoteAddress(), cause);
        Sockets.closeOnFlushed(inbound);
    }
}
