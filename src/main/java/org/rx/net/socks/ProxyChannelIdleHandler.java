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

    @Override
    protected IdleStateEvent newIdleStateEvent(IdleState state, boolean first) {
        log.info("idle {}", state);
        return super.newIdleStateEvent(state, first);
    }

    @SneakyThrows
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            log.info("{} {} idle: {}", Sockets.protocolName(ctx.channel()), ctx.channel(), ((IdleStateEvent) evt).state());
            Sockets.closeOnFlushed(ctx.channel());
            SocksContext sc = SocksContext.ctx(ctx.channel());
            if (sc.onClose != null) {
                sc.onClose.invoke();
            }
            return;
        }
        super.userEventTriggered(ctx, evt);
    }
}
