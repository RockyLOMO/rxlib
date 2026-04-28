package org.rx.net.dns;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.DatagramPacket;

import java.net.InetSocketAddress;

@ChannelHandler.Sharable
final class DnsDatagramSourceHandler extends ChannelInboundHandlerAdapter {
    static final DnsDatagramSourceHandler DEFAULT = new DnsDatagramSourceHandler();

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof DatagramPacket) {
            InetSocketAddress sender = ((DatagramPacket) msg).sender();
            if (sender != null) {
                ctx.channel().attr(DnsServer.ATTR_UDP_SENDER).set(sender);
            }
        }
        super.channelRead(ctx, msg);
    }
}
