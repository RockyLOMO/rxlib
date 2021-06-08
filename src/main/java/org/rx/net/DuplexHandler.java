package org.rx.net;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class DuplexHandler extends ChannelInboundHandlerAdapter {
    public ChannelFuture write(ChannelHandlerContext ctx, Object msg) {
        checkWritable(ctx.channel());
        return ctx.write(msg);
    }

    public ChannelFuture writeAndFlush(ChannelHandlerContext ctx, Object msg) {
        checkWritable(ctx.channel());
        return ctx.writeAndFlush(msg);
    }

    @SneakyThrows
    private void checkWritable(Channel channel) {
        if (!channel.isWritable()) {
            log.warn("Client not writable");
            synchronized (this) {
                wait();
            }
        }
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) {
        if (ctx.channel().isWritable()) {
            synchronized (this) {
                notifyAll();
            }
        }
    }
}
