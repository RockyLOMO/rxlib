package org.rx.net.socks;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@ChannelHandler.Sharable
public class Socks5UdpAssociateHandler extends ChannelInboundHandlerAdapter {
    public static final Socks5UdpAssociateHandler DEFAULT = new Socks5UdpAssociateHandler();

//    public Socks5UdpAssociateHandler() {
//
//    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        log.info("UdpAssociate {} active", ctx.channel().remoteAddress());
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        log.info("UdpAssociate {} inactive", ctx.channel().remoteAddress());
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("UdpAssociate {}", cause.getMessage());
    }
}
