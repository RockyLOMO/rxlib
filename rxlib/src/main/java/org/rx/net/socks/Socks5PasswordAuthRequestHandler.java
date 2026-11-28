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
        Authenticator authenticator = server.getAuthenticator();
        if (authenticator == null) {
            ctx.writeAndFlush(new DefaultSocks5PasswordAuthResponse(Socks5PasswordAuthStatus.FAILURE)).addListener(ChannelFutureListener.CLOSE);
            return;
        }
        String coreAuthUser = ctx.channel().attr(org.rx.net.transport.core.TransportCoreCarrier.ATTR_CORE_AUTH_USER).get();
        if (coreAuthUser != null && !coreAuthUser.isEmpty() && !coreAuthUser.equals(msg.username())) {
            log.warn("socks5[{}] auth identity mismatch coreUser={} socksUser={}",
                    server.getConfig().getListenPort(), coreAuthUser, msg.username());
            ctx.writeAndFlush(new DefaultSocks5PasswordAuthResponse(Socks5PasswordAuthStatus.FAILURE)).addListener(ChannelFutureListener.CLOSE);
            return;
        }
        AuthResult result = authenticator.loginResult(msg.username(), msg.password());
        if (result == null || result.getUser() == null
                || (authenticator != Authenticator.NON_AUTH && result.getUser().isAnonymous())) {
            ctx.writeAndFlush(new DefaultSocks5PasswordAuthResponse(Socks5PasswordAuthStatus.FAILURE)).addListener(ChannelFutureListener.CLOSE);
            return;
        }
        user = result.getUser();
        trafficUser = result.getTrafficUser();
        ProxyManageHandler.get(ctx).setUser(user, trafficUser, ctx);
        if (Boolean.TRUE.equals(ctx.channel().attr(org.rx.net.transport.core.TransportCoreCarrier.ATTR_LIGHTWEIGHT_NEGOTIATED).get())) {
            String pwd = msg.password();
            ctx.channel().attr(org.rx.net.transport.core.TransportCoreCarrier.ATTR_LIGHTWEIGHT_AUTH_SECRET)
                    .set(pwd == null ? null : pwd.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            // LightweightServerExtHandler flushes RFC 1929 SUCCESS + 43-byte HMAC-authenticated ack in ONE TCP segment.
            return;
        }
        ctx.writeAndFlush(SUCCESS);
    }
}
