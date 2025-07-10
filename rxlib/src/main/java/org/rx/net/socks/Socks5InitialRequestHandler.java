package org.rx.net.socks;

import io.netty.channel.*;
import io.netty.handler.codec.socksx.SocksVersion;
import io.netty.handler.codec.socksx.v5.DefaultSocks5InitialRequest;
import io.netty.handler.codec.socksx.v5.DefaultSocks5InitialResponse;
import io.netty.handler.codec.socksx.v5.Socks5AuthMethod;
import io.netty.handler.codec.socksx.v5.Socks5InitialRequestDecoder;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.cache.DiskCache;
import org.rx.net.Sockets;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Set;

import static org.rx.core.Sys.toJsonString;

@Slf4j
@ChannelHandler.Sharable
public class Socks5InitialRequestHandler extends SimpleChannelInboundHandler<DefaultSocks5InitialRequest> {
    public static final Socks5InitialRequestHandler DEFAULT = new Socks5InitialRequestHandler();

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DefaultSocks5InitialRequest msg) {
        ChannelPipeline pipeline = ctx.pipeline();
        pipeline.remove(Socks5InitialRequestDecoder.class.getSimpleName());
        pipeline.remove(this);
//        log.debug("socks5[{}] init connect: {}", server.getConfig().getListenPort(), msg);

        SocksProxyServer server = Sockets.getAttr(ctx.channel(), SocksContext.SOCKS_SVR);
        Set<InetAddress> whiteList = server.getConfig().getWhiteList();
        InetSocketAddress remoteEp = (InetSocketAddress) ctx.channel().remoteAddress();
        InetAddress raddr = remoteEp.getAddress();
        if (!raddr.isLoopbackAddress() && !Sockets.isPrivateIp(raddr)
                && !whiteList.contains(raddr)) {
            DiskCache.iteratorContext(0, Integer.MAX_VALUE, InetAddress.class);
            log.warn("socks5[{}] whiteList={}\n{} access blocked", server.getConfig().getListenPort(), toJsonString(whiteList), remoteEp);
            ctx.close();
            return;
        }
        if (msg.decoderResult().isFailure() || !msg.version().equals(SocksVersion.SOCKS5)) {
            log.warn("socks5[{}] error protocol", server.getConfig().getListenPort(), msg.decoderResult().cause());
            ctx.writeAndFlush(new DefaultSocks5InitialResponse(Socks5AuthMethod.UNACCEPTED)).addListener(ChannelFutureListener.CLOSE);
            return;
        }
        ctx.writeAndFlush(new DefaultSocks5InitialResponse(server.isAuthEnabled() ? Socks5AuthMethod.PASSWORD : Socks5AuthMethod.NO_AUTH));
    }
}
