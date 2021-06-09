package org.rx.net.socks;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.handler.codec.compression.ZlibCodecFactory;
import io.netty.handler.codec.compression.ZlibWrapper;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
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
public class SslDirectServer extends Disposable {
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
            InetSocketAddress proxyEndpoint = proxyRule.invoke((InetSocketAddress) inbound.channel().remoteAddress());
            log.debug("connect to backend {}", proxyEndpoint);
            Bootstrap bootstrap = Sockets.bootstrap(inbound.channel().eventLoop(), MemoryMode.LOW, channel -> {
                ChannelPipeline pipeline = channel.pipeline();
                if (config.getEnableFlags() != null && config.getEnableFlags().has(SslDirectConfig.EnableFlags.BACKEND)) {
                    if (config.isEnableSsl()) {
                        SslContext sslCtx = SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build();
                        pipeline.addLast(sslCtx.newHandler(channel.alloc(), proxyEndpoint.getHostString(), proxyEndpoint.getPort()));
                    }
                    if (config.isEnableCompress()) {
                        pipeline.addLast(ZlibCodecFactory.newZlibEncoder(ZlibWrapper.GZIP), ZlibCodecFactory.newZlibDecoder(ZlibWrapper.GZIP));
                    }
                }
                pipeline.addLast(new BackendHandler(inbound));
            });
            outbound = bootstrap.connect(proxyEndpoint).channel();
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

    final SslDirectConfig config;
    final ServerBootstrap serverBootstrap;
    final BiFunc<InetSocketAddress, InetSocketAddress> proxyRule;

    public SslDirectServer(@NonNull SslDirectConfig config, @NonNull BiFunc<InetSocketAddress, InetSocketAddress> proxyRule) {
        this.config = config;
        serverBootstrap = Sockets.serverBootstrap(channel -> {
            ChannelPipeline pipeline = channel.pipeline();
            if (config.getEnableFlags() != null && config.getEnableFlags().has(SslDirectConfig.EnableFlags.FRONTEND)) {
                if (config.isEnableSsl()) {
                    SelfSignedCertificate ssc = new SelfSignedCertificate();
                    SslContext sslCtx = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();
                    pipeline.addLast(sslCtx.newHandler(channel.alloc()));
                }
                if (config.isEnableCompress()) {
                    pipeline.addLast(ZlibCodecFactory.newZlibEncoder(ZlibWrapper.GZIP),
                            ZlibCodecFactory.newZlibDecoder(ZlibWrapper.GZIP));
                }
            }
            pipeline.addLast(new FrontendHandler());
        });
        serverBootstrap.bind(config.getListenPort());
        log.debug("DirectProxy Listened on port {}..", config.getListenPort());
        this.proxyRule = proxyRule;
    }

    @Override
    protected void freeObjects() {
        Sockets.closeBootstrap(serverBootstrap);
    }
}
