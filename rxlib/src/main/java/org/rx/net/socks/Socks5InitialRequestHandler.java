package org.rx.net.socks;

import io.netty.channel.*;
import io.netty.handler.codec.socksx.SocksVersion;
import io.netty.handler.codec.socksx.v5.DefaultSocks5InitialRequest;
import io.netty.handler.codec.socksx.v5.DefaultSocks5InitialResponse;
import io.netty.handler.codec.socksx.v5.Socks5AuthMethod;
import io.netty.handler.codec.socksx.v5.Socks5InitialRequestDecoder;
import lombok.extern.slf4j.Slf4j;
import org.rx.net.Sockets;

import java.net.InetAddress;
import java.net.InetSocketAddress;

@Slf4j
@ChannelHandler.Sharable
public class Socks5InitialRequestHandler extends SimpleChannelInboundHandler<DefaultSocks5InitialRequest> {
    public static final Socks5InitialRequestHandler DEFAULT = new Socks5InitialRequestHandler();
    static final DefaultSocks5InitialResponse AUTH = new DefaultSocks5InitialResponse(Socks5AuthMethod.PASSWORD);

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DefaultSocks5InitialRequest msg) {
        ChannelPipeline pipeline = ctx.pipeline();
        pipeline.remove(Socks5InitialRequestDecoder.class.getSimpleName());
        pipeline.remove(this);
//        log.debug("socks5[{}] init connect: {}", server.getConfig().getListenPort(), msg);

        SocksProxyServer server = Sockets.getAttr(ctx.channel(), SocksContext.SOCKS_SVR);
        SocksConfig config = server.getConfig();
        if (config.isWhiteListEnabled()) {
            InetSocketAddress remoteEp = Sockets.getOriginRemoteAddress(ctx.channel());
            InetAddress raddr = remoteEp.getAddress();
            if (!config.isAllowed(raddr)) {
                log.warn("socks5[{}] whiteListSize={} {} access blocked",
                        config.getListenPort(), config.getWhiteList().size(), remoteEp);
                ctx.close();
                return;
            }
        }
        if (msg.decoderResult().isFailure() || !msg.version().equals(SocksVersion.SOCKS5)) {
            log.warn("socks5[{}] error protocol", server.getConfig().getListenPort(), msg.decoderResult().cause());
            ctx.writeAndFlush(new DefaultSocks5InitialResponse(Socks5AuthMethod.UNACCEPTED)).addListener(ChannelFutureListener.CLOSE);
            return;
        }
        if (!server.isAuthEnabled()) {
            if (!msg.authMethods().contains(Socks5AuthMethod.NO_AUTH)) {
                ctx.writeAndFlush(new DefaultSocks5InitialResponse(Socks5AuthMethod.UNACCEPTED)).addListener(ChannelFutureListener.CLOSE);
                return;
            }
            ctx.writeAndFlush(new DefaultSocks5InitialResponse(Socks5AuthMethod.NO_AUTH));
            return;
        }
        if (server.getAuthenticator() == Authenticator.NON_AUTH) {
            if (msg.authMethods().contains(Socks5AuthMethod.PASSWORD)) {
                ctx.writeAndFlush(AUTH);
            } else {
                ctx.writeAndFlush(new DefaultSocks5InitialResponse(Socks5AuthMethod.UNACCEPTED)).addListener(ChannelFutureListener.CLOSE);
            }
            return;
        }
        boolean allowLightweight = (config.isRssPrivateTransport() || config.isAdaptiveTcp())
                && config.effectiveHandshakeMode() == org.rx.net.HandshakeMode.LIGHTWEIGHT
                && !org.rx.net.transport.core.TransportCoreCarrier.isAeadAuthenticated(pipeline);
        if (allowLightweight && msg.authMethods().contains(org.rx.net.transport.core.TransportCoreCarrier.METHOD_RSS_LIGHTWEIGHT)) {
            ctx.channel().attr(org.rx.net.transport.core.TransportCoreCarrier.ATTR_LIGHTWEIGHT_NEGOTIATED).set(Boolean.TRUE);
            String authHandlerName = Socks5PasswordAuthRequestHandler.class.getSimpleName();
            if (pipeline.context(authHandlerName) != null) {
                pipeline.addAfter(authHandlerName,
                        org.rx.net.transport.core.TransportCoreCarrier.LIGHTWEIGHT_EXT_HANDLER,
                        new org.rx.net.transport.core.TransportCoreCarrier.LightweightServerExtHandler());
            }
            ctx.writeAndFlush(new DefaultSocks5InitialResponse(org.rx.net.transport.core.TransportCoreCarrier.METHOD_RSS_LIGHTWEIGHT));
            return;
        }
        if (msg.authMethods().contains(Socks5AuthMethod.PASSWORD)) {
            ctx.writeAndFlush(AUTH);
            return;
        }
        ctx.writeAndFlush(new DefaultSocks5InitialResponse(Socks5AuthMethod.UNACCEPTED)).addListener(ChannelFutureListener.CLOSE);
    }
}
