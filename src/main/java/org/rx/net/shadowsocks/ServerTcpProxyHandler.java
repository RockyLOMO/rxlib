package org.rx.net.shadowsocks;

import io.netty.channel.*;
import lombok.extern.slf4j.Slf4j;
import org.rx.bean.SUID;
import org.rx.core.App;
import org.rx.core.Arrays;
import org.rx.exception.ExceptionHandler;
import org.rx.net.Sockets;
import org.rx.net.socks.BackendRelayHandler;
import org.rx.net.socks.FrontendRelayHandler;
import org.rx.net.socks.RouteEventArgs;
import org.rx.net.socks.SocksContext;
import org.rx.net.socks.upstream.Upstream;
import org.rx.net.support.SocksSupport;
import org.rx.net.support.UnresolvedEndpoint;

import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentLinkedQueue;

@Slf4j
@ChannelHandler.Sharable
public class ServerTcpProxyHandler extends ChannelInboundHandlerAdapter {
    public static final ServerTcpProxyHandler DEFAULT = new ServerTcpProxyHandler();

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        Channel inbound = ctx.channel();
        SocksContext.tcpOutbound(inbound, () -> {
            ShadowsocksServer server = SocksContext.ssServer(inbound);
            InetSocketAddress realEp = inbound.attr(SSCommon.REMOTE_DEST).get();

            RouteEventArgs e = new RouteEventArgs((InetSocketAddress) inbound.remoteAddress(), new UnresolvedEndpoint(realEp));
            server.raiseEvent(server.onRoute, e);
            Upstream upstream = e.getValue();
            UnresolvedEndpoint dstEp = upstream.getDestination();

            if (SocksSupport.FAKE_IPS.contains(dstEp.getHost()) || !Sockets.isValidIp(dstEp.getHost())) {
                long hash = App.hash64(dstEp.toString());
                SocksSupport.fakeDict().putIfAbsent(hash, dstEp);
//                log.info("fakeEp {} {}", hash, dstEp);
                dstEp = new UnresolvedEndpoint(String.format("%s%s", hash, SocksSupport.FAKE_HOST_SUFFIX), Arrays.randomNext(SocksSupport.FAKE_PORT_OBFS));
            }

            ConcurrentLinkedQueue<Object> pendingPackages = new ConcurrentLinkedQueue<>();
            UnresolvedEndpoint finalDestinationEp = dstEp;
            Channel channel = Sockets.bootstrap(inbound.eventLoop(), server.config, outbound -> {
                upstream.initChannel(outbound);
                outbound.pipeline().addLast(new BackendRelayHandler(inbound, pendingPackages));
            }).connect(dstEp.socketAddress()).addListener((ChannelFutureListener) f -> {
                if (!f.isSuccess()) {
                    ExceptionHandler.INSTANCE.log("connect to backend {}[{}] fail", finalDestinationEp, realEp, f.cause());
                    Sockets.closeOnFlushed(inbound);
                    return;
                }
                log.debug("connect to backend {}[{}]", finalDestinationEp, realEp);

                SocksSupport.ENDPOINT_TRACER.link(inbound, f.channel());
            }).channel();
            inbound.pipeline().addLast(new FrontendRelayHandler(channel, pendingPackages));
            return channel;
        });

        ctx.fireChannelRead(msg).pipeline().remove(this);
    }
}
