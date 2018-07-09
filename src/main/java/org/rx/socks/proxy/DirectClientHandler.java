package org.rx.socks.proxy;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import java.util.function.BiConsumer;

import static org.rx.Contract.require;

public class DirectClientHandler extends SimpleChannelInboundHandler<byte[]> {
    private BiConsumer<ChannelHandlerContext, byte[]> onReceive;
    private ChannelHandlerContext                     ctx;

    public DirectClientHandler(BiConsumer<ChannelHandlerContext, byte[]> onReceive) {
        require(onReceive);

        this.onReceive = onReceive;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        this.ctx = ctx;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, byte[] bytes) {
        if (onReceive != null) {
            onReceive.accept(ctx, bytes);
        }
    }

    public ChannelFuture send(byte[] bytes) {
        require(bytes);

        return ctx.writeAndFlush(bytes);
    }

    public ChannelFuture send(ByteBuf bytes) {
        require(bytes);

        return ctx.writeAndFlush(bytes);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        ctx.close();
    }
}
