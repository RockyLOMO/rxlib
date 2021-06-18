package org.rx.net.shadowsocks.socks5;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rx.net.Sockets;

//// src <- des
//outbound.pipeline().addLast(new RelayHandler(ctx.channel()));
//// src -> des
//ctx.pipeline().addLast(new RelayHandler(outbound));
@Slf4j
@RequiredArgsConstructor
public final class RelayHandler extends ChannelInboundHandlerAdapter {
    final Channel relayChannel;

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        ctx.writeAndFlush(Unpooled.EMPTY_BUFFER);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (relayChannel.isActive()) {
            relayChannel.writeAndFlush(msg);
        } else {
            ReferenceCountUtil.release(msg);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        Sockets.closeOnFlushed(relayChannel);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("caught", cause);
        Sockets.closeOnFlushed(ctx.channel());
    }
}
