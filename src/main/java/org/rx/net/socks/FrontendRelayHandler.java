package org.rx.net.socks;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rx.net.Sockets;

import java.util.Collection;

@Slf4j
@RequiredArgsConstructor
public class FrontendRelayHandler extends ChannelInboundHandlerAdapter {
    public static final String PIPELINE_NAME = "to-upstream";
    final Channel outbound;
    final Collection<Object> pendingPackages;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        Channel inbound = ctx.channel();
        if (!outbound.isActive()) {
            if (pendingPackages != null) {
                log.debug("PENDING_QUEUE {} => {} pend a packet", inbound.remoteAddress(), outbound);
                pendingPackages.add(msg);
            }
            return;
        }

        log.debug("RELAY {} => {}[{}]", inbound.remoteAddress(), outbound.localAddress(), outbound.remoteAddress());
        outbound.writeAndFlush(msg);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        Sockets.closeOnFlushed(outbound);
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        Channel inbound = ctx.channel();
        log.warn("RELAY {} => {}[{}] thrown", inbound.remoteAddress(), outbound.localAddress(), outbound.remoteAddress(), cause);
        Sockets.closeOnFlushed(inbound);
    }
}
