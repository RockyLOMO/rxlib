package org.rx.net.socks;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.socksx.v5.DefaultSocks5PasswordAuthRequest;
import io.netty.handler.codec.socksx.v5.DefaultSocks5PasswordAuthResponse;
import io.netty.handler.codec.socksx.v5.Socks5PasswordAuthRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5PasswordAuthStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class Socks5PasswordAuthRequestHandler extends SimpleChannelInboundHandler<DefaultSocks5PasswordAuthRequest> {
    private final SocksProxyServer socksProxyServer;

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DefaultSocks5PasswordAuthRequest msg) throws Exception {
        ChannelPipeline pipeline = ctx.pipeline();
        pipeline.remove(Socks5PasswordAuthRequestDecoder.class.getSimpleName());
        pipeline.remove(this);
        log.debug("socks5 auth {}:{}", msg.username(), msg.password());

        if (socksProxyServer.getAuthenticator() == null || !socksProxyServer.getAuthenticator().auth(msg.username(), msg.password())) {
            ProxyChannelManageHandler.username(ctx, "unauthorized");
            ctx.writeAndFlush(new DefaultSocks5PasswordAuthResponse(Socks5PasswordAuthStatus.FAILURE)).addListener(ChannelFutureListener.CLOSE);
            return;
        }
        ProxyChannelManageHandler.username(ctx, msg.username());
        ctx.writeAndFlush(new DefaultSocks5PasswordAuthResponse(Socks5PasswordAuthStatus.SUCCESS));
    }
}
