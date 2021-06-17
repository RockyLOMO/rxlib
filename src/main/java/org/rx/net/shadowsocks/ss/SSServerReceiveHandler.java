package org.rx.net.shadowsocks.ss;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;

import java.net.InetSocketAddress;

public class SSServerReceiveHandler extends SimpleChannelInboundHandler<Object> {
    public SSServerReceiveHandler() {
        super(false);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {
        boolean isUdp = ctx.channel().attr(SSCommon.IS_UDP).get();
        if (isUdp) {
            DatagramPacket udpRaw = ((DatagramPacket) msg);
            if (udpRaw.content().readableBytes() < 4) { //no cipher, min size = 1 + 1 + 2 ,[1-byte type][variable-length host][2-byte port]
                return;
            }
            ctx.channel().attr(SSCommon.REMOTE_ADDR).set(udpRaw.sender());
            ctx.fireChannelRead(udpRaw.content());
            return;
        }

        ctx.channel().attr(SSCommon.REMOTE_ADDR).set((InetSocketAddress) ctx.channel().remoteAddress());
        ctx.channel().pipeline().remove(this);
        ctx.fireChannelRead(msg);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        super.exceptionCaught(ctx, cause);
    }
}
