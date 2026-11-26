package org.rx.net.socks;

import io.netty.channel.*;
import io.netty.handler.codec.socksx.v5.DefaultSocks5PasswordAuthRequest;
import io.netty.handler.codec.socksx.v5.DefaultSocks5PasswordAuthResponse;
import io.netty.handler.codec.socksx.v5.Socks5PasswordAuthRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5PasswordAuthStatus;
import lombok.extern.slf4j.Slf4j;
import org.rx.net.Sockets;

@Slf4j
@ChannelHandler.Sharable
public class Socks5PasswordAuthRequestHandler extends SimpleChannelInboundHandler<DefaultSocks5PasswordAuthRequest> {
    public static final Socks5PasswordAuthRequestHandler DEFAULT = new Socks5PasswordAuthRequestHandler();
    static final DefaultSocks5PasswordAuthResponse SUCCESS = new DefaultSocks5PasswordAuthResponse(Socks5PasswordAuthStatus.SUCCESS);

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DefaultSocks5PasswordAuthRequest msg) {
        ChannelPipeline pipeline = ctx.pipeline();
        pipeline.remove(Socks5PasswordAuthRequestDecoder.class.getSimpleName());
        pipeline.remove(this);
//        log.debug("socks5 auth {}:{}", msg.username(), msg.password());

        SocksProxyServer server = Sockets.getAttr(ctx.channel(), SocksContext.SOCKS_SVR);
        SocksUser user;
        TrafficUser trafficUser = null;
        if (server.getAuthenticator() == null) {
            ctx.writeAndFlush(new DefaultSocks5PasswordAuthResponse(Socks5PasswordAuthStatus.FAILURE)).addListener(ChannelFutureListener.CLOSE);
            return;
        }
        AuthResult result = server.getAuthenticator().loginResult(msg.username(), msg.password());
        Integer profile = ctx.channel().attr(SocksAdaptiveHandshake.NEGOTIATED).getAndSet(null);
        Boolean clientNoHop = ctx.channel().attr(SocksAdaptiveHandshake.NO_HOP).getAndSet(null);
        boolean udpHop = !Boolean.TRUE.equals(clientNoHop) && server.getConfig().isUdpHop();
        byte[] nonce = profile != null
                ? SocksAdaptiveHandshake.sendNonce(ctx, server.adaptivePort, udpHop) : null;
        if (result == null || nonce != null && (result.getUser() == null || result.getUser().isAnonymous()
                || msg.password().isEmpty())) {
            ctx.writeAndFlush(new DefaultSocks5PasswordAuthResponse(Socks5PasswordAuthStatus.FAILURE)).addListener(ChannelFutureListener.CLOSE);
            return;
        }
        user = result.getUser();
        trafficUser = result.getTrafficUser();
        ChannelFuture success = ctx.writeAndFlush(SUCCESS);
        if (nonce != null) {
            success.addListener(f -> {
                if (f.isSuccess()) {
                    SocksAdaptiveHandshake.install(ctx.channel(), nonce, msg.password(), server.adaptivePort, profile, udpHop);
                } else {
                    ctx.close();
                }
            });
        }
        ProxyManageHandler.get(ctx).setUser(user, trafficUser, ctx);
    }
}
