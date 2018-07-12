package org.rx.socks.proxy;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.rx.Logger;

import java.util.function.BiConsumer;

import static org.rx.Contract.require;

public class DirectClientHandler extends SimpleChannelInboundHandler<byte[]> {
    private BiConsumer<ChannelHandlerContext, byte[]> onReceive;
    private ChannelHandlerContext                     ctx;

    public Channel getChannel() {
        require(ctx);
        return ctx.channel();
    }

    public DirectClientHandler(BiConsumer<ChannelHandlerContext, byte[]> onReceive) {
        require(onReceive);

        this.onReceive = onReceive;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
        this.ctx = ctx;
        Logger.info("DirectClientHandler %s connect %s", ctx.channel().localAddress(), ctx.channel().remoteAddress());
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, byte[] bytes) {
        onReceive.accept(ctx, bytes);
        Logger.info("DirectClientHandler %s recv %s bytes from %s", ctx.channel().remoteAddress(), bytes.length,
                ctx.channel().localAddress());
    }

    public ChannelFuture send(byte[] bytes) {
        try {
            return ctx.channel().writeAndFlush(bytes);
        } finally {
            Logger.info("DirectClientHandler %s send %s bytes to %s", ctx.channel().localAddress(), bytes.length,
                    ctx.channel().remoteAddress());
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        super.exceptionCaught(ctx, cause);
        Logger.error(cause, "DirectClientHandler");
        ctx.close();
    }
}
