package org.rx.net.socks;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import lombok.Getter;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Disposable;
import org.rx.net.Sockets;
import org.rx.util.function.BiFunc;

import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentLinkedQueue;

@Slf4j
public final class SslDirectServer extends Disposable {
    class RequestHandler extends ChannelInboundHandlerAdapter {
        @SneakyThrows
        @Override
        public void channelActive(ChannelHandlerContext inbound) {
            InetSocketAddress proxyEndpoint = router.invoke((InetSocketAddress) inbound.channel().remoteAddress());
            log.debug("connect to backend {}", proxyEndpoint);
            ConcurrentLinkedQueue<Object> pendingPackages = new ConcurrentLinkedQueue<>();
            Bootstrap bootstrap = Sockets.bootstrap(inbound.channel().eventLoop(), null, channel -> {
                ChannelPipeline pipeline = channel.pipeline();
                SslUtil.addBackendHandler(channel, config.getTransportFlags(), proxyEndpoint, false);
                pipeline.addLast(ForwardingBackendHandler.PIPELINE_NAME, new ForwardingBackendHandler(inbound, pendingPackages));
            });
            bootstrap.connect(proxyEndpoint).addListener((ChannelFutureListener) f -> {
                if (!f.isSuccess()) {
                    log.error("Connect to backend {} fail", proxyEndpoint, f.cause());
                    Sockets.closeOnFlushed(inbound.channel());
                    return;
                }
                Channel outbound = f.channel();
                inbound.pipeline().replace(this, ForwardingFrontendHandler.PIPELINE_NAME, new ForwardingFrontendHandler(outbound, pendingPackages));
            });
        }
    }

    @Getter
    final SslDirectConfig config;
    final ServerBootstrap serverBootstrap;
    final BiFunc<InetSocketAddress, InetSocketAddress> router;

    public SslDirectServer(@NonNull SslDirectConfig config, @NonNull BiFunc<InetSocketAddress, InetSocketAddress> router) {
        this.config = config;
        serverBootstrap = Sockets.serverBootstrap(channel -> {
            ChannelPipeline pipeline = channel.pipeline();
            SslUtil.addFrontendHandler(channel, config.getTransportFlags());
            pipeline.addLast(new RequestHandler());
        });
        serverBootstrap.bind(config.getListenPort()).addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess()) {
                log.error("Listened on port {} fail", config.getListenPort(), f.cause());
            }
        });
        this.router = router;
    }

    @Override
    protected void freeObjects() {
        Sockets.closeBootstrap(serverBootstrap);
    }
}
