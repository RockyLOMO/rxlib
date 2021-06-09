package org.rx.net.socks;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Disposable;
import org.rx.net.MemoryMode;
import org.rx.net.Sockets;
import org.rx.util.function.BiFunc;

import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentLinkedQueue;

@Slf4j
public final class SslDirectServer extends Disposable {
    class FrontendHandler extends ChannelInboundHandlerAdapter {
        @RequiredArgsConstructor
        class BackendHandler extends ChannelInboundHandlerAdapter {
            final ChannelHandlerContext inbound;

            @Override
            public void channelActive(ChannelHandlerContext ctx) {
                flushBackend();
            }

            @Override
            public void channelRead(ChannelHandlerContext outbound, Object msg) {
                if (!inbound.channel().isActive()) {
                    return;
                }
                inbound.writeAndFlush(msg);
            }

            @Override
            public void channelInactive(ChannelHandlerContext ctx) {
                if (!inbound.channel().isActive()) {
                    return;
                }
                Sockets.closeOnFlushed(inbound.channel());
            }

            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                log.error("serverCaught {}", ctx.channel().remoteAddress(), cause);
                Sockets.closeOnFlushed(ctx.channel());
            }
        }

        Channel outbound;
        final ConcurrentLinkedQueue<Object> packetQueue = new ConcurrentLinkedQueue<>();

        @SneakyThrows
        @Override
        public void channelActive(ChannelHandlerContext inbound) {
            InetSocketAddress proxyEndpoint = router.invoke((InetSocketAddress) inbound.channel().remoteAddress());
            log.debug("connect to backend {}", proxyEndpoint);
            Bootstrap bootstrap = Sockets.bootstrap(inbound.channel().eventLoop(), MemoryMode.LOW, channel -> {
                ChannelPipeline pipeline = channel.pipeline();
                SslUtil.addBackendHandler(channel, config.getTransportFlags(), proxyEndpoint, false);
                pipeline.addLast(new BackendHandler(inbound));
            });
            outbound = bootstrap.connect(proxyEndpoint).addListener((ChannelFutureListener) f -> {
                if (!f.isSuccess()) {
                    log.error("Connect to backend {} fail", proxyEndpoint, f.cause());
                    Sockets.closeOnFlushed(inbound.channel());
                }
            }).channel();
        }

        @Override
        public void channelRead(ChannelHandlerContext inbound, Object msg) {
            if (!outbound.isActive()) {
                packetQueue.add(msg);
                return;
            }
            flushBackend();
            outbound.writeAndFlush(msg);
        }

        @Override
        public void channelInactive(ChannelHandlerContext inbound) {
            if (!outbound.isActive()) {
                return;
            }
            Sockets.closeOnFlushed(outbound);
        }

        private void flushBackend() {
            if (packetQueue.isEmpty()) {
                return;
            }
            log.debug("flushBackend");
            Sockets.writeAndFlush(outbound, packetQueue);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.error("serverCaught {}", ctx.channel().remoteAddress(), cause);
            Sockets.closeOnFlushed(ctx.channel());
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
            SslUtil.appendFrontendHandler(channel, config.getTransportFlags());
            pipeline.addLast(new FrontendHandler());
        });
        serverBootstrap.bind(config.getListenPort()).addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess()) {
                log.error("Listen on port {} fail", config.getListenPort(), f.cause());
                return;
            }
            log.debug("Listened on port {}..", config.getListenPort());
        });
        this.router = router;
    }

    @Override
    protected void freeObjects() {
        Sockets.closeBootstrap(serverBootstrap);
    }
}
