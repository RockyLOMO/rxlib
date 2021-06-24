package org.rx.net.socks;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import lombok.Getter;
import lombok.NonNull;
import lombok.SneakyThrows;
import org.rx.core.Disposable;
import org.rx.net.Sockets;
import org.rx.net.TransportUtil;
import org.rx.util.function.BiFunc;

import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class DirectProxyServer extends Disposable {
    class RequestHandler extends ChannelInboundHandlerAdapter {
        @SneakyThrows
        @Override
        public void channelActive(ChannelHandlerContext inbound) {
            InetSocketAddress proxyEndpoint = router.invoke((InetSocketAddress) inbound.channel().remoteAddress());
            ConcurrentLinkedQueue<Object> pendingPackages = new ConcurrentLinkedQueue<>();
            Bootstrap bootstrap = Sockets.bootstrap(inbound.channel().eventLoop(), null, channel -> {
                ChannelPipeline pipeline = channel.pipeline();
                TransportUtil.addBackendHandler(channel, config, proxyEndpoint);
                pipeline.addLast(ForwardingBackendHandler.PIPELINE_NAME, new ForwardingBackendHandler(inbound, pendingPackages));
            });
            bootstrap.connect(proxyEndpoint).addListeners(Sockets.logConnect(proxyEndpoint), (ChannelFutureListener) f -> {
                if (!f.isSuccess()) {
                    Sockets.closeOnFlushed(inbound.channel());
                    return;
                }
                Channel outbound = f.channel();
                inbound.pipeline().replace(this, ForwardingFrontendHandler.PIPELINE_NAME, new ForwardingFrontendHandler(outbound, pendingPackages));
            });
        }
    }

    @Getter
    final DirectConfig config;
    final ServerBootstrap serverBootstrap;
    final BiFunc<InetSocketAddress, InetSocketAddress> router;

    public DirectProxyServer(@NonNull DirectConfig config, @NonNull BiFunc<InetSocketAddress, InetSocketAddress> router) {
        this.config = config;
        serverBootstrap = Sockets.serverBootstrap(channel -> {
            ChannelPipeline pipeline = channel.pipeline();
            TransportUtil.addFrontendHandler(channel, config);
            pipeline.addLast(new RequestHandler());
        });
        serverBootstrap.bind(config.getListenPort()).addListener(Sockets.logBind(config.getListenPort()));
        this.router = router;
    }

    @Override
    protected void freeObjects() {
        Sockets.closeBootstrap(serverBootstrap);
    }
}
