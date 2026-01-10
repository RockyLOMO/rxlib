package org.rx.net.shadowsocks;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;

import java.net.InetSocketAddress;

@ChannelHandler.Sharable
public class ServerSendHandler extends ChannelOutboundHandlerAdapter {
    public static final ServerSendHandler DEFAULT = new ServerSendHandler();

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        Channel inbound = ctx.channel();
        boolean isUdp = inbound instanceof DatagramChannel;
        if (isUdp) {
            ByteBuf buf = (ByteBuf) msg;
            InetSocketAddress clientAddr = inbound.attr(SSCommon.REMOTE_ADDRESS).get();
            msg = new DatagramPacket(buf, clientAddr);
        }
        super.write(ctx, msg, promise);
    }
}
