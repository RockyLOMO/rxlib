package org.rx.net;

import io.netty.channel.*;
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
        super.write(ctx, msg, promise.addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE));
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        final Channel channel = ctx.channel();
        if (channel.isWritable()) {
            synchronized (channel) {
                log.info("{} {} writable", Sockets.protocolName(channel), channel);
                channel.notifyAll();
            }
        }
        super.channelWritabilityChanged(ctx);
    }
}
