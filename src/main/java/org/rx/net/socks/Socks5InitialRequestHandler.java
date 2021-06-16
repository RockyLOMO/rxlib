package org.rx.net.socks;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.socksx.SocksVersion;
import io.netty.handler.codec.socksx.v5.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rx.net.Sockets;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Set;

@Slf4j
@RequiredArgsConstructor
public class Socks5InitialRequestHandler extends SimpleChannelInboundHandler<DefaultSocks5InitialRequest> {
    final SocksProxyServer server;

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DefaultSocks5InitialRequest msg) {
        ChannelPipeline pipeline = ctx.pipeline();
        pipeline.remove(Socks5InitialRequestDecoder.class.getSimpleName());
        pipeline.remove(this);
//        log.debug("socks5[{}] init connect: {}", server.getConfig().getListenPort(), msg);

        Set<InetAddress> whiteList = server.getConfig().getWhiteList();
        if (whiteList.isEmpty()) {
            log.warn("socks5[{}] whiteList is empty", server.getConfig().getListenPort());
            ctx.close();
            return;
        }
        InetSocketAddress remoteEp = (InetSocketAddress) ctx.channel().remoteAddress();
        if (!Sockets.isNatIp(remoteEp.getAddress()) && !whiteList.contains(remoteEp.getAddress())) {
            log.warn("socks5[{}] {} access blocked", server.getConfig().getListenPort(), remoteEp);
            ctx.close();
            return;
        }
        if (msg.decoderResult().isFailure() || !msg.version().equals(SocksVersion.SOCKS5)) {
            log.warn("socks5[{}] error protocol", server.getConfig().getListenPort(), msg.decoderResult().cause());
//            ctx.fireChannelRead(msg);
            ctx.writeAndFlush(new DefaultSocks5InitialResponse(Socks5AuthMethod.UNACCEPTED)).addListener(ChannelFutureListener.CLOSE);
            return;
        }
        ctx.writeAndFlush(new DefaultSocks5InitialResponse(server.isAuthEnabled() ? Socks5AuthMethod.PASSWORD : Socks5AuthMethod.NO_AUTH));
    }
}
