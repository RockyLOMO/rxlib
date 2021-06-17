package org.rx.net.shadowsocks;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.net.Sockets;
import org.rx.net.socks.ForwardingBackendHandler;
import org.rx.net.socks.upstream.Upstream;
import org.rx.net.support.UnresolvedEndpoint;

import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

@Slf4j
@RequiredArgsConstructor
public class SSServerTcpProxyHandler extends SimpleChannelInboundHandler<ByteBuf> {
    final ShadowsocksServer server;
    Channel outbound;
    final ConcurrentLinkedQueue<Object> pendingPackages = new ConcurrentLinkedQueue<>();

    @SneakyThrows
    @Override
    public void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) throws Exception {
        Channel inbound = ctx.channel();
        if (outbound == null) {
            InetSocketAddress clientRecipient = inbound.attr(SSCommon.REMOTE_DEST).get();

            UnresolvedEndpoint destinationEndpoint = new UnresolvedEndpoint(clientRecipient.getHostString(), clientRecipient.getPort());
            Upstream upstream = server.router.invoke(destinationEndpoint);

            Bootstrap bootstrap = Sockets.bootstrap(inbound.eventLoop(), server.config, ch -> ch.pipeline().addLast(new IdleStateHandler(0, 0, SSCommon.TCP_PROXY_IDLE_TIME, TimeUnit.SECONDS) {
                @Override
                protected IdleStateEvent newIdleStateEvent(IdleState state, boolean first) {
                    log.debug("{} state:{}", clientRecipient, state);
                    Sockets.closeOnFlushed(outbound);
                    return super.newIdleStateEvent(state, first);
                }
            }, new ForwardingBackendHandler(ctx, pendingPackages)));
            outbound = bootstrap.connect(clientRecipient).addListener((ChannelFutureListener) f -> {
                if (!f.isSuccess()) {
                    log.error("Connect to backend {} fail", clientRecipient, f.cause());
                    Sockets.closeOnFlushed(inbound);
                }
            }).channel();
        }

        if (!outbound.isActive()) {
            log.debug("add pending packages");
            pendingPackages.add(msg.retain());
            return;
        }

        log.debug("{} forwarded to {} -> {}", inbound.remoteAddress(), outbound.localAddress(), outbound.remoteAddress());
        outbound.writeAndFlush(msg.retain()).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        Sockets.closeOnFlushed(outbound);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        Channel inbound = ctx.channel();
        log.warn("{} forwarded to {} -> {} thrown", inbound.remoteAddress(), outbound.localAddress(), outbound.remoteAddress(), cause);
        Sockets.closeOnFlushed(inbound);
    }
}
