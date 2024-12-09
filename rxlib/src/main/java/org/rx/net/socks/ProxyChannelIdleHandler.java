package org.rx.net.socks;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.net.Sockets;

@Slf4j
public class ProxyChannelIdleHandler extends IdleStateHandler {
    public ProxyChannelIdleHandler(int readerIdleTimeSeconds, int writerIdleTimeSeconds) {
        super(readerIdleTimeSeconds, writerIdleTimeSeconds, 0);
    }

    //userEventTriggered not fire
    @SneakyThrows
    @Override
    protected void channelIdle(ChannelHandlerContext ctx, IdleStateEvent evt) {
        Channel channel = ctx.channel();
        log.info("{} {} idle: {}", Sockets.protocolName(channel), channel, evt.state());
        SocksContext sc = SocksContext.ctx(channel, false);
        if (sc != null && sc.onClose != null) {
            sc.onClose.invoke();
        }
        super.channelIdle(ctx, evt);
        Sockets.closeOnFlushed(channel);
    }
}
