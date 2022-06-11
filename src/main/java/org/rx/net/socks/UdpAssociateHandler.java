package org.rx.net.socks;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.extern.slf4j.Slf4j;
import org.rx.net.Sockets;

@Slf4j
@ChannelHandler.Sharable
public class UdpAssociateHandler extends ChannelInboundHandlerAdapter {
    public static final UdpAssociateHandler DEFAULT = new UdpAssociateHandler();

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        log.info("xxudp inactive");
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
//        Channel inbound = ctx.channel();
//        SocksContext sc = SocksContext.ctx(inbound);
//        log.warn("RELAY {} => {}[{}] thrown", inbound.remoteAddress(), sc.outbound.localAddress(), sc.outbound.remoteAddress(), cause);
//        Sockets.closeOnFlushed(inbound);
        log.info("xxudp", cause);
    }
}
