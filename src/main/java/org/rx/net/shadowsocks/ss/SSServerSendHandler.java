package org.rx.net.shadowsocks.ss;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.socket.DatagramPacket;

import java.net.InetSocketAddress;

public class SSServerSendHandler extends ChannelOutboundHandlerAdapter {
    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        boolean isUdp = ctx.channel().attr(SSCommon.IS_UDP).get();
        if (isUdp) {
            InetSocketAddress clientAddr = ctx.channel().attr(SSCommon.REMOTE_ADDR).get();
            msg = new DatagramPacket((ByteBuf) msg, clientAddr);
        }
        super.write(ctx, msg, promise);
    }
}
