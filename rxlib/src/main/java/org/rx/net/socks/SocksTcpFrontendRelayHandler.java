package org.rx.net.socks;

import io.netty.channel.*;
import lombok.extern.slf4j.Slf4j;
import org.rx.net.Sockets;

@Slf4j
@ChannelHandler.Sharable
public class SocksTcpFrontendRelayHandler extends ChannelInboundHandlerAdapter {
    public static final SocksTcpFrontendRelayHandler DEFAULT = new SocksTcpFrontendRelayHandler();

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        Channel inbound = ctx.channel();
        SocksContext sc = SocksContext.ctx(inbound);
        if (!sc.outboundActive) {
            sc.outbound.addListener((ChannelFutureListener) f -> {
                if (!f.isSuccess()) {
                    return;
                }
                log.debug("TCP outbound pending FLUSH_PACK {} => {}", inbound.remoteAddress(), sc.outbound);
                f.channel().writeAndFlush(msg);
            });
            return;
        }

        Channel outbound = sc.outbound.channel();
        log.debug("TCP RELAY {} => {}[{}]", inbound.remoteAddress(), outbound.localAddress(), outbound.remoteAddress());
        outbound.writeAndFlush(msg);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        Channel inbound = ctx.channel();
        SocksContext sc = SocksContext.ctx(inbound);
        super.channelInactive(ctx);
        Sockets.closeOnFlushed(sc.outbound.channel());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        Channel inbound = ctx.channel();
        SocksContext sc = SocksContext.ctx(inbound);
        Channel outbound = sc.outbound.channel();
        log.warn("TCP RELAY {} => {}[{}] thrown", inbound.remoteAddress(), outbound.localAddress(), outbound.remoteAddress(), cause);
        Sockets.closeOnFlushed(inbound);
    }
}
