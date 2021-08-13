package org.rx.net.shadowsocks;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.socket.DatagramPacket;
import org.rx.core.App;

import java.net.InetSocketAddress;

@ChannelHandler.Sharable
public class ServerSendHandler extends ChannelOutboundHandlerAdapter {
    public static final ServerSendHandler DEFAULT = new ServerSendHandler();

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        boolean isUdp = ctx.channel().attr(SSCommon.IS_UDP).get();
        if (isUdp) {
            ByteBuf buf = (ByteBuf) msg;
            InetSocketAddress clientAddr = ctx.channel().attr(SSCommon.REMOTE_ADDRESS).get();
            msg = new DatagramPacket(buf, clientAddr);
            App.log("UdpTo {}", msg);
        }
        super.write(ctx, msg, promise);
    }
}
