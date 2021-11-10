package org.rx.net;

import io.netty.channel.*;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@ChannelHandler.Sharable
final class WaterMarkHandler extends ChannelDuplexHandler {
    public static final WaterMarkHandler DEFAULT = new WaterMarkHandler();

    private WaterMarkHandler() {
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        final Channel channel = ctx.channel();
        if (!channel.isWritable()) {
            synchronized (channel) {
                if (!channel.isWritable()) {
                    log.warn("{} {} not writable", Sockets.protocolName(channel), channel);
                    channel.wait();
                }
            }
        }
        log.info("RELEASE {} => {}", ReferenceCountUtil.refCnt(msg), msg);
        super.write(ctx, msg, promise.addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE));
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        doNotify(ctx.channel());
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        doNotify(ctx.channel());
        super.exceptionCaught(ctx, cause);
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        final Channel channel = ctx.channel();
        if (channel.isWritable()) {
            doNotify(channel);
        }
        super.channelWritabilityChanged(ctx);
    }

    private void doNotify(Channel channel) {
        synchronized (channel) {
            log.info("{} {} writable", Sockets.protocolName(channel), channel);
            channel.notifyAll();
        }
    }
}
