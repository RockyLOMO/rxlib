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

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@RequiredArgsConstructor
public class Socks5PasswordAuthRequestHandler extends SimpleChannelInboundHandler<DefaultSocks5PasswordAuthRequest> {
    final SocksProxyServer server;

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DefaultSocks5PasswordAuthRequest msg) {
        ChannelPipeline pipeline = ctx.pipeline();
        pipeline.remove(Socks5PasswordAuthRequestDecoder.class.getSimpleName());
        pipeline.remove(this);
        log.debug("socks5 auth {}:{}", msg.username(), msg.password());

        SocksUser user;
        if (server.getAuthenticator() == null || (user = server.getAuthenticator().login(msg.username(), msg.password())) == null) {
            ctx.writeAndFlush(new DefaultSocks5PasswordAuthResponse(Socks5PasswordAuthStatus.FAILURE)).addListener(ChannelFutureListener.CLOSE);
            return;
        }
        AtomicInteger refCnt = user.getLoginIps().computeIfAbsent(((InetSocketAddress) ctx.channel().remoteAddress()).getAddress(), k -> new AtomicInteger());
        refCnt.incrementAndGet();
        ProxyManageHandler.get(ctx).setUser(user);
        ctx.writeAndFlush(new DefaultSocks5PasswordAuthResponse(Socks5PasswordAuthStatus.SUCCESS));
    }
}
