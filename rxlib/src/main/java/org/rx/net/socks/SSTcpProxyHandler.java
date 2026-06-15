package org.rx.net.socks;

import io.netty.channel.*;
import lombok.extern.slf4j.Slf4j;
import org.rx.net.Sockets;
import org.rx.net.TcpBackpressureHandler;
import org.rx.net.socks.upstream.Upstream;
import org.rx.net.support.EndpointTracer;

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

        SocksContext e = SocksContext.getCtx(Sockets.getOriginRemoteAddress(inbound), dstEp);
        server.publishEvent(server.onTcpRoute, e);
        Upstream upstream = e.getUpstream();
        InetSocketAddress upDstEp = upstream.getDestination();

        ChannelFuture outboundFuture = Sockets.bootstrap(inbound.eventLoop(), upstream.getConfig(), upstream.connectAddressHint(), outbound -> {
            upstream.initChannel(outbound);
            inbound.pipeline().addLast(SocksTcpFrontendRelayHandler.DEFAULT);
        }).connect(upDstEp).addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess()) {
                logConnectFailure(upDstEp, dstEp, f.cause());
                Sockets.closeOnFlushed(inbound);
                return;
            }
            if (debug) {
                log.info("SS TCP connect to backend {}[{}]", upDstEp, dstEp);
            }
            Channel outbound = f.channel();
            TcpBackpressureHandler.installPair(inbound, outbound);
            EndpointTracer.TCP.link(Sockets.getOriginRemoteAddress(inbound), outbound);
            outbound.pipeline().addLast(SocksTcpBackendRelayHandler.DEFAULT);
        });
        SocksContext.markCtx(inbound, outboundFuture, e);

        ctx.fireChannelRead(msg).pipeline().remove(this);
    }

    static void logConnectFailure(InetSocketAddress upDstEp, InetSocketAddress dstEp, Throwable cause) {
        log.warn("SS TCP connect to backend {}[{}] fail source={} cause={} message={}",
                upDstEp, dstEp, SSTcpProxyHandler.class.getName(),
                cause == null ? "unknown" : cause.getClass().getName(),
                cause == null ? "" : cause.getMessage());
        if (log.isDebugEnabled()) {
            log.debug("SS TCP connect to backend {}[{}] full failure", upDstEp, dstEp, cause);
        }
    }
}
