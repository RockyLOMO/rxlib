package org.rx.net.shadowsocks;

import io.netty.channel.*;
import lombok.extern.slf4j.Slf4j;
import org.rx.exception.TraceHandler;
import org.rx.net.Sockets;
import org.rx.net.socks.SocksContext;
import org.rx.net.socks.SocksTcpBackendRelayHandler;
import org.rx.net.socks.SocksTcpFrontendRelayHandler;
import org.rx.net.socks.upstream.Upstream;
import org.rx.net.support.SocksSupport;
import org.rx.net.support.UnresolvedEndpoint;

import java.net.InetSocketAddress;

@Slf4j
@ChannelHandler.Sharable
public class ServerTcpProxyHandler extends ChannelInboundHandlerAdapter {
    public static final ServerTcpProxyHandler DEFAULT = new ServerTcpProxyHandler();

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        Channel inbound = ctx.channel();
        ShadowsocksServer server = Sockets.getAttr(inbound, SocksContext.SS_SVR);
        boolean debug = server.config.isDebug();
        InetSocketAddress dstEp = inbound.attr(SSCommon.REMOTE_DEST).get();

        SocksContext e = new SocksContext((InetSocketAddress) inbound.remoteAddress(), new UnresolvedEndpoint(dstEp));
        server.raiseEvent(server.onRoute, e);
        Upstream upstream = e.getUpstream();
        UnresolvedEndpoint upDstEp = upstream.getDestination();

        ChannelFuture outboundFuture = Sockets.bootstrap(inbound.eventLoop(), upstream.getConfig(), outbound -> {
            upstream.initChannel(outbound);
            inbound.pipeline().addLast(SocksTcpFrontendRelayHandler.DEFAULT);
        }).connect(upDstEp.socketAddress()).addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess()) {
                log.warn("SS TCP connect to backend {}[{}] fail", upDstEp, dstEp, f.cause());
                Sockets.closeOnFlushed(inbound);
                return;
            }
            if (debug) {
                log.info("SS TCP connect to backend {}[{}]", upDstEp, dstEp);
            }
            Channel outbound = f.channel();
            SocksSupport.ENDPOINT_TRACER.link(inbound, outbound);
            outbound.pipeline().addLast(SocksTcpBackendRelayHandler.DEFAULT);
        });
        SocksContext.mark(inbound, outboundFuture, e);

        ctx.fireChannelRead(msg).pipeline().remove(this);
    }
}
