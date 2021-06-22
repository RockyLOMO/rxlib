package org.rx.net.socks;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleStateEvent;
import lombok.extern.slf4j.Slf4j;
import org.rx.net.Sockets;

@Slf4j
public class ProxyChannelIdleHandler extends ChannelInboundHandlerAdapter {
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            log.info("{} idle timeout: {}", ctx.channel().remoteAddress(), ((IdleStateEvent) evt).state());
            Sockets.closeOnFlushed(ctx.channel());
            return;
        }
        super.userEventTriggered(ctx, evt);
    }
}
