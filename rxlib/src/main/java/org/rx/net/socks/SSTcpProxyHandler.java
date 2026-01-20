package org.rx.net.socks;

import io.netty.channel.*;
import lombok.extern.slf4j.Slf4j;
import org.rx.net.Sockets;
import org.rx.net.socks.upstream.Upstream;
import org.rx.net.support.UnresolvedEndpoint;

import java.net.InetSocketAddress;

@Slf4j
@ChannelHandler.Sharable
public class SSTcpProxyHandler extends ChannelInboundHandlerAdapter {
    public static final SSTcpProxyHandler DEFAULT = new SSTcpProxyHandler();

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        Channel inbound = ctx.channel();
        ShadowsocksServer server = Sockets.getAttr(inbound, ShadowsocksConfig.SVR);
        boolean debug = server.config.isDebug();
        InetSocketAddress dstEp = inbound.attr(ShadowsocksConfig.REMOTE_DEST).get();

        SocksContext e = SocksContext.getCtx((InetSocketAddress) inbound.remoteAddress(), new UnresolvedEndpoint(dstEp));
        server.raiseEvent(server.onTcpRoute, e);
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
            SocksRpcContract.ENDPOINT_TRACER.link(inbound, outbound);
            outbound.pipeline().addLast(SocksTcpBackendRelayHandler.DEFAULT);
        });
        SocksContext.markCtx(inbound, outboundFuture, e);

        ctx.fireChannelRead(msg).pipeline().remove(this);
    }
}
