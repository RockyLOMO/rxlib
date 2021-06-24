package org.rx.net;

import io.netty.channel.*;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@ChannelHandler.Sharable
public final class DuplexHandler extends ChannelDuplexHandler {
    public static final DuplexHandler DEFAULT = new DuplexHandler();

    private DuplexHandler() {
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        final Channel channel = ctx.channel();
        if (!channel.isWritable()) {
            synchronized (channel) {
                if (!channel.isWritable()) {
                    log.error("Channel {} not writable", channel);
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
                log.warn("Channel {} writable callback", channel);
                channel.notifyAll();
            }
        }
        super.channelWritabilityChanged(ctx);
    }
}
