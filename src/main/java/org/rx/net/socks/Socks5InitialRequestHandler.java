package org.rx.net.socks;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.socksx.SocksVersion;
import io.netty.handler.codec.socksx.v5.DefaultSocks5InitialRequest;
import io.netty.handler.codec.socksx.v5.DefaultSocks5InitialResponse;
import io.netty.handler.codec.socksx.v5.Socks5AuthMethod;
import io.netty.handler.codec.socksx.v5.Socks5InitialResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class Socks5InitialRequestHandler extends SimpleChannelInboundHandler<DefaultSocks5InitialRequest> {
    private final ProxyServer proxyServer;

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DefaultSocks5InitialRequest msg) throws Exception {
        log.debug("socks5 init connect: {}", msg);
        if (msg.decoderResult().isFailure()) {
            log.debug("socks5 error protocol");
            ctx.fireChannelRead(msg);
            return;
        }
        if (msg.version().equals(SocksVersion.SOCKS5)) {
            Socks5InitialResponse initialResponse;
            if (proxyServer.isAuth()) {
                initialResponse = new DefaultSocks5InitialResponse(Socks5AuthMethod.PASSWORD);
            } else {
                initialResponse = new DefaultSocks5InitialResponse(Socks5AuthMethod.NO_AUTH);
            }
            ctx.writeAndFlush(initialResponse);
        }
    }
}
