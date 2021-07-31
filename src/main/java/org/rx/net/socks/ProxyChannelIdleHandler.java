package org.rx.net.socks;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.timeout.IdleStateEvent;
import lombok.extern.slf4j.Slf4j;
import org.rx.net.Sockets;

@Slf4j
@ChannelHandler.Sharable
public class ProxyChannelIdleHandler extends ChannelInboundHandlerAdapter {
    public static final ProxyChannelIdleHandler DEFAULT = new ProxyChannelIdleHandler();

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            String ch = ctx.channel() instanceof NioDatagramChannel ? "UDP" : "TCP";
            log.info("{} {} idle timeout: {}", ch, ctx.channel().remoteAddress(), ((IdleStateEvent) evt).state());
            Sockets.closeOnFlushed(ctx.channel());
            return;
        }
        super.userEventTriggered(ctx, evt);
    }
}
