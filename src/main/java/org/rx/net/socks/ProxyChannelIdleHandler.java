package org.rx.net.socks;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.timeout.IdleState;
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

    public ProxyChannelIdleHandler(int readerIdleTimeSeconds, int writerIdleTimeSeconds, int allIdleTimeSeconds) {
        super(readerIdleTimeSeconds, writerIdleTimeSeconds, allIdleTimeSeconds);
    }

//    @Override
//    protected IdleStateEvent newIdleStateEvent(IdleState state, boolean first) {
//        log.info("idle0 {}", state);
//        return super.newIdleStateEvent(state, first);
//    }

    //userEventTriggered 不触发
    @SneakyThrows
    @Override
    protected void channelIdle(ChannelHandlerContext ctx, IdleStateEvent evt) {
        log.info("{} {} idle: {}", Sockets.protocolName(ctx.channel()), ctx.channel(), evt.state());
        Sockets.closeOnFlushed(ctx.channel());
        SocksContext sc = SocksContext.ctx(ctx.channel());
        if (sc.onClose != null) {
            sc.onClose.invoke();
        }
        super.channelIdle(ctx, evt);
    }
}
