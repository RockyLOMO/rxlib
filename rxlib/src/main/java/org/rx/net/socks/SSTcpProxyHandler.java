package org.rx.net.socks;

import io.netty.channel.*;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;
import org.rx.net.BackpressureHandler;
import org.rx.net.Sockets;
import org.rx.net.socks.upstream.Upstream;
import org.rx.net.support.EndpointTracer;
import org.rx.net.support.UnresolvedEndpoint;

import java.net.SocketAddress;

@Slf4j
@ChannelHandler.Sharable
public class SSTcpProxyHandler extends ChannelInboundHandlerAdapter {
    public static final SSTcpProxyHandler DEFAULT = new SSTcpProxyHandler();

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        Channel inbound = ctx.channel();
        ShadowsocksServer server = Sockets.getAttr(inbound, ShadowsocksConfig.SVR);
        boolean debug = server.config.isDebug();
        UnresolvedEndpoint dstEp = inbound.attr(ShadowsocksConfig.REMOTE_DEST).get();
        if (dstEp == null) {
            log.warn("SS TCP missing destination, close {}", Sockets.getOriginRemoteAddress(inbound));
            ReferenceCountUtil.release(msg);
            inbound.close();
            return;
        }

        SocksContext e = SocksContext.getCtx(Sockets.getOriginRemoteAddress(inbound), dstEp);
        try {
            server.publishEvent(server.onTcpRoute, e);
        } catch (Throwable routeError) {
            log.warn("SS TCP route error for {}", dstEp, routeError);
            ReferenceCountUtil.release(msg);
            inbound.close();
            return;
        }

        Upstream upstream = e.getUpstream();
        if (upstream == null || upstream.getDestination() == null) {
            log.warn("SS TCP route upstream invalid for {} from {}", dstEp, Sockets.getOriginRemoteAddress(inbound));
            ReferenceCountUtil.release(msg);
            inbound.close();
            return;
        }

        UnresolvedEndpoint upDstEp = upstream.getDestination();
        SocketAddress connectAddress;
        try {
            connectAddress = upDstEp.socketAddress();
        } catch (Throwable routeError) {
            log.warn("SS TCP route destination invalid for {} from {}", upDstEp, Sockets.getOriginRemoteAddress(inbound), routeError);
            ReferenceCountUtil.release(msg);
            inbound.close();
            return;
        }

        ChannelFuture outboundFuture = Sockets.bootstrap(inbound.eventLoop(), upstream.getConfig(), upstream.connectAddressHint(), outbound -> {
            upstream.initChannel(outbound);
            inbound.pipeline().addLast(SocksTcpFrontendRelayHandler.DEFAULT);
        }).connect(connectAddress).addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess()) {
                log.warn("SS TCP connect to backend {}[{}] fail", upDstEp, dstEp, f.cause());
                inbound.close();
                return;
            }
            if (debug) {
                log.info("SS TCP connect to backend {}[{}]", upDstEp, dstEp);
            }
            Channel outbound = f.channel();
//            BackpressureHandler.install(inbound, outbound);
            EndpointTracer.TCP.link(Sockets.getOriginRemoteAddress(inbound), outbound);
            outbound.pipeline().addLast(SocksTcpBackendRelayHandler.DEFAULT);
        });
        SocksContext.markCtx(inbound, outboundFuture, e);

        ctx.fireChannelRead(msg).pipeline().remove(this);
    }
}
