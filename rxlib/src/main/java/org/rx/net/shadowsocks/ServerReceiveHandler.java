package org.rx.net.shadowsocks;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;

import java.net.InetSocketAddress;

@ChannelHandler.Sharable
public class ServerReceiveHandler extends SimpleChannelInboundHandler<Object> {
    public static final ServerReceiveHandler DEFAULT = new ServerReceiveHandler();

    public ServerReceiveHandler() {
        super(false);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {
        Channel inbound = ctx.channel();
        boolean isUdp = inbound instanceof DatagramChannel;
        if (isUdp) {
            DatagramPacket udpRaw = ((DatagramPacket) msg);
            ByteBuf buf = udpRaw.content();
            if (buf.readableBytes() < 4) { //no cipher, min size = 1 + 1 + 2 ,[1-byte type][variable-length host][2-byte port]
                return;
            }
            inbound.attr(SSCommon.REMOTE_ADDRESS).set(udpRaw.sender());
            ctx.fireChannelRead(buf);
            return;
        }

        inbound.attr(SSCommon.REMOTE_ADDRESS).set((InetSocketAddress) inbound.remoteAddress());
        inbound.pipeline().remove(this);
        ctx.fireChannelRead(msg);
    }
}
