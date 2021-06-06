package org.rx.net;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.exception.InvalidException;

@Slf4j
public abstract class ChannelClientHandler extends ChannelInboundHandlerAdapter implements AutoCloseable {
    private volatile ChannelHandlerContext context;

    public boolean isConnected() {
        return context != null && context.channel().isActive();
    }

    public Channel channel() {
        if (!isConnected()) {
            throw new InvalidException("Client not active");
        }
        return context.channel();
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
//        super.channelActive(ctx);
        this.context = ctx;
    }

    public ChannelFuture write(Object msg) {
        checkWritable();
        return context.write(msg);
    }

    public ChannelFuture writeAndFlush(Object msg) {
        checkWritable();
        return context.writeAndFlush(msg);
    }

    @SneakyThrows
    private void checkWritable() {
        Channel channel = channel();
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

    @Override
    public void close() {
        if (!isConnected()) {
            return;
        }

        Sockets.closeOnFlushed(context.channel());
    }
}
