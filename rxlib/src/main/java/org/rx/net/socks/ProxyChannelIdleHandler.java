package org.rx.net.socks;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.extern.slf4j.Slf4j;
import org.rx.net.Sockets;

import java.util.concurrent.TimeUnit;

@Slf4j
public class ProxyChannelIdleHandler extends IdleStateHandler {
    public ProxyChannelIdleHandler(int readerIdleTimeSeconds, int writerIdleTimeSeconds) {
        super(readerIdleTimeSeconds, writerIdleTimeSeconds, 0);
    }

    public ProxyChannelIdleHandler(int readerIdleTimeSeconds, int writerIdleTimeSeconds, int allIdleTimeSeconds) {
        // Observe pending output for TCP relay idle checks; keep directional callers unchanged.
        super(allIdleTimeSeconds > 0, readerIdleTimeSeconds, writerIdleTimeSeconds, allIdleTimeSeconds, TimeUnit.SECONDS);
    }

    //userEventTriggered not fire
    @Override
    protected void channelIdle(ChannelHandlerContext ctx, IdleStateEvent evt) throws Exception {
        Channel channel = ctx.channel();
        log.info("{} {} idle: {}", Sockets.protocolName(channel), channel, evt.state());
        super.channelIdle(ctx, evt);
        Sockets.closeOnFlushed(channel);
    }
}
