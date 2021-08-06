package org.rx.net.shadowsocks;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.bean.SUID;
import org.rx.core.Arrays;
import org.rx.net.Sockets;
import org.rx.net.socks.ForwardingBackendHandler;
import org.rx.net.socks.upstream.Upstream;
import org.rx.net.support.SocksSupport;
import org.rx.net.support.UnresolvedEndpoint;

import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

@Slf4j
@RequiredArgsConstructor
public class ServerTcpProxyHandler extends SimpleChannelInboundHandler<ByteBuf> {
    final ShadowsocksServer server;
    final ConcurrentLinkedQueue<Object> pendingPackages = new ConcurrentLinkedQueue<>();
    Channel outbound;

    @SneakyThrows
    @Override
    public void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) throws Exception {
        Channel inbound = ctx.channel();
        if (outbound == null) {
            InetSocketAddress realEp = inbound.attr(SSCommon.REMOTE_DEST).get();
            Upstream upstream = server.router.invoke(new UnresolvedEndpoint(realEp.getHostString(), realEp.getPort()));
            UnresolvedEndpoint dstEp = upstream.getDestination();

            if (SocksSupport.FAKE_IPS.contains(dstEp.getHost()) || !Sockets.isValidIp(dstEp.getHost())) {
                SUID hash = SUID.compute(dstEp.toString());
                SocksSupport.fakeDict().putIfAbsent(hash, dstEp);
                dstEp = new UnresolvedEndpoint(String.format("%s%s", hash, SocksSupport.FAKE_HOST_SUFFIX), Arrays.randomGet(SocksSupport.FAKE_PORT_OBFS));
            }

            UnresolvedEndpoint finalDestinationEp = dstEp;
            outbound = Sockets.bootstrap(inbound.eventLoop(), server.config, ch -> {
                ChannelPipeline pipeline = ch.pipeline();
                pipeline.addLast(new IdleStateHandler(0, 0, server.config.getTcpIdleTime(), TimeUnit.SECONDS) {
                    @Override
                    protected IdleStateEvent newIdleStateEvent(IdleState state, boolean first) {
                        log.info("{}[{}] timeout {}", finalDestinationEp, realEp, state);
                        Sockets.closeOnFlushed(outbound);
                        return super.newIdleStateEvent(state, first);
                    }
                });
                upstream.initChannel(ch);
                pipeline.addLast(new ForwardingBackendHandler(ctx, pendingPackages));
            }).connect(dstEp.toSocketAddress()).addListener((ChannelFutureListener) f -> {
                if (!f.isSuccess()) {
                    log.error("connect to backend {}[{}] fail", finalDestinationEp, realEp, f.cause());
                    Sockets.closeOnFlushed(inbound);
                    return;
                }
                log.info("connect to backend {}[{}]", finalDestinationEp, realEp);

                SocksSupport.ENDPOINT_TRACER.link(inbound, outbound);
            }).channel();
        }

        if (!outbound.isActive()) {
            InetSocketAddress dstEndpoint = inbound.attr(SSCommon.REMOTE_DEST).get();
            log.debug("{} pending forwarded to {}", inbound.remoteAddress(), dstEndpoint);
            pendingPackages.add(msg.retain());
            return;
        }

        log.debug("{} forwarded to {} -> {}", inbound.remoteAddress(), outbound.localAddress(), outbound.remoteAddress());
        outbound.writeAndFlush(msg.retain()).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (outbound == null) {
            return;
        }

        Sockets.closeOnFlushed(outbound);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        Channel inbound = ctx.channel();
        log.warn("{} forwarded to {} -> {} thrown", inbound.remoteAddress(), outbound.localAddress(), outbound.remoteAddress(), cause);
        Sockets.closeOnFlushed(inbound);
    }
}
