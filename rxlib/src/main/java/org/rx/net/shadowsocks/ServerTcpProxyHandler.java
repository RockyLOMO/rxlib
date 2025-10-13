package org.rx.net.shadowsocks;

import io.netty.channel.*;
import lombok.extern.slf4j.Slf4j;
import org.rx.exception.TraceHandler;
import org.rx.net.Sockets;
import org.rx.net.socks.BackendRelayHandler;
import org.rx.net.socks.FrontendRelayHandler;
import org.rx.net.socks.SocksContext;
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
        InetSocketAddress realEp = inbound.attr(SSCommon.REMOTE_DEST).get();

        SocksContext e = new SocksContext((InetSocketAddress) inbound.remoteAddress(), new UnresolvedEndpoint(realEp));
        server.raiseEvent(server.onRoute, e);
        UnresolvedEndpoint dstEp = e.getUpstream().getDestination();

        Sockets.bootstrap(inbound.eventLoop(), server.config, outbound -> {
            e.getUpstream().initChannel(outbound);

            SocksContext.mark(inbound, outbound, e, true);
            inbound.pipeline().addLast(FrontendRelayHandler.DEFAULT);
        }).connect(dstEp.socketAddress()).addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess()) {
                TraceHandler.INSTANCE.log("connect to backend {}[{}] fail", dstEp, realEp, f.cause());
                Sockets.closeOnFlushed(inbound);
                return;
            }
            log.debug("connect to backend {}[{}]", dstEp, realEp);
            Channel outbound = f.channel();
            SocksSupport.ENDPOINT_TRACER.link(inbound, outbound);
            outbound.pipeline().addLast(BackendRelayHandler.DEFAULT);
        });

        ctx.fireChannelRead(msg).pipeline().remove(this);
    }
}
