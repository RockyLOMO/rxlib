package org.rx.net.socks;

import io.netty.channel.*;
import io.netty.handler.codec.socksx.v5.DefaultSocks5PasswordAuthRequest;
import io.netty.handler.codec.socksx.v5.DefaultSocks5PasswordAuthResponse;
import io.netty.handler.codec.socksx.v5.Socks5PasswordAuthRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5PasswordAuthStatus;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@ChannelHandler.Sharable
public class Socks5PasswordAuthRequestHandler extends SimpleChannelInboundHandler<DefaultSocks5PasswordAuthRequest> {
    public static final Socks5PasswordAuthRequestHandler DEFAULT = new Socks5PasswordAuthRequestHandler();

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DefaultSocks5PasswordAuthRequest msg) {
        ChannelPipeline pipeline = ctx.pipeline();
        pipeline.remove(Socks5PasswordAuthRequestDecoder.class.getSimpleName());
        pipeline.remove(this);
//        log.debug("socks5 auth {}:{}", msg.username(), msg.password());

        SocksProxyServer server = SocksContext.getAttr(ctx.channel(), SocksContext.SOCKS_SVR);
        SocksUser user;
        if (server.getAuthenticator() == null || (user = server.getAuthenticator().login(msg.username(), msg.password())) == null) {
            ctx.writeAndFlush(new DefaultSocks5PasswordAuthResponse(Socks5PasswordAuthStatus.FAILURE)).addListener(ChannelFutureListener.CLOSE);
            return;
        }
        ctx.writeAndFlush(new DefaultSocks5PasswordAuthResponse(Socks5PasswordAuthStatus.SUCCESS));
        ProxyManageHandler.get(ctx).setUser(user, ctx);
    }
}
