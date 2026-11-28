package org.rx.net.socks;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.util.ReferenceCountUtil;
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
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof ChannelInputShutdownEvent) {
            ctx.close();
            return;
        }
        super.userEventTriggered(ctx, evt);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        try {
            connect(ctx);
        } catch (Throwable error) {
            ReferenceCountUtil.release(msg);
            ctx.close();
            throw error;
        }
        ctx.fireChannelRead(msg);
        ctx.pipeline().remove(this);
    }

    private void connect(ChannelHandlerContext ctx) {
        Channel inbound = ctx.channel();
        ShadowsocksServer server = Sockets.getAttr(inbound, ShadowsocksConfig.SVR);
        boolean debug = server.config.isDebug();
        InetSocketAddress dstEp = inbound.attr(ShadowsocksConfig.REMOTE_DEST).get();

        SocksContext e = SocksContext.getCtx(Sockets.getOriginRemoteAddress(inbound), dstEp);
        server.publishEvent(server.onTcpRoute, e);
        Upstream upstream = e.getUpstream();
        InetSocketAddress upDstEp = upstream.getDestination();

        InetSocketAddress originRemoteAddr = Sockets.getOriginRemoteAddress(inbound);
        inbound.pipeline().addLast(SocksTcpFrontendRelayHandler.DEFAULT);
        Bootstrap outboundBootstrap = Sockets.bootstrap(inbound.eventLoop(), upstream.getConfig(), upstream.connectAddressHint(), outbound -> {
            if (originRemoteAddr != null) {
                outbound.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelActive(ChannelHandlerContext octx) throws Exception {
                        EndpointTracer.TCP.link(originRemoteAddr, octx.channel());
                        octx.pipeline().remove(this);
                        super.channelActive(octx);
                    }
                });
            }
            upstream.initChannel(outbound);
        });
        ChannelFuture outboundFuture = outboundBootstrap.connect(upDstEp).addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess()) {
                logConnectFailure(upDstEp, dstEp, f.cause());
                Sockets.closeOnFlushed(inbound);
                return;
            }
            if (debug) {
                log.info("SS TCP connect to backend {}[{}]", upDstEp, dstEp);
            }
            Channel outbound = f.channel();
            if (!inbound.isActive()) {
                outbound.close();
                return;
            }
            TcpBackpressureHandler.installPair(inbound, outbound);
            EndpointTracer.TCP.link(Sockets.getOriginRemoteAddress(inbound), outbound);
            outbound.pipeline().addLast(SocksTcpBackendRelayHandler.DEFAULT);
        });
        SocksContext.markCtx(inbound, outboundFuture, e);
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
