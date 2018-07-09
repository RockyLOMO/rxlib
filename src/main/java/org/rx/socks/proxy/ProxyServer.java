package org.rx.socks.proxy;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.bytes.ByteArrayDecoder;
import io.netty.handler.codec.bytes.ByteArrayEncoder;
import io.netty.handler.codec.compression.ZlibCodecFactory;
import io.netty.handler.codec.compression.ZlibWrapper;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import lombok.SneakyThrows;
import org.rx.Disposable;

import java.net.SocketAddress;

import static org.rx.Contract.require;

public final class ProxyServer extends Disposable {
    private static class DirectServerInitializer extends ChannelInitializer<SocketChannel> {
        private final SslContext sslCtx;
        private boolean          enableCompression;

        public DirectServerInitializer(SslContext sslCtx, boolean enableCompression) {
            this.sslCtx = sslCtx;
            this.enableCompression = enableCompression;
        }

        @Override
        public void initChannel(SocketChannel ch) {
            ChannelPipeline pipeline = ch.pipeline();
            if (sslCtx != null) {
                pipeline.addLast(sslCtx.newHandler(ch.alloc()));
            }
            if (enableCompression) {
                // Enable stream compression (you can remove these two if unnecessary)
                pipeline.addLast(ZlibCodecFactory.newZlibEncoder(ZlibWrapper.GZIP));
                pipeline.addLast(ZlibCodecFactory.newZlibDecoder(ZlibWrapper.GZIP));
            }

            // Add the number codec first,
            pipeline.addLast(new ByteArrayDecoder());
            pipeline.addLast(new ByteArrayEncoder());

            // and then business logic.
            // Please note we create a handler for every new channel because it has stateful properties.
            pipeline.addLast(new DirectServerHandler(sslCtx != null, enableCompression));
        }
    }

    private EventLoopGroup group;
    private boolean        enableSsl, enableCompression;

    public boolean isEnableSsl() {
        return enableSsl;
    }

    public void setEnableSsl(boolean enableSsl) {
        this.enableSsl = enableSsl;
    }

    public boolean isEnableCompression() {
        return enableCompression;
    }

    public void setEnableCompression(boolean enableCompression) {
        this.enableCompression = enableCompression;
    }

    public boolean isBusy() {
        return group != null;
    }

    public ProxyServer() {
        enableCompression = true;
    }

    @Override
    protected void freeUnmanaged() {
        if (group != null) {
            group.shutdownGracefully();
        }
    }

    @SneakyThrows
    public void start(SocketAddress localAddress) {
        checkNotClosed();
        require(group == null);

        // Configure SSL.
        SslContext sslCtx = null;
        if (enableSsl) {
            SelfSignedCertificate ssc = new SelfSignedCertificate();
            sslCtx = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();
        }

        ServerBootstrap b = new ServerBootstrap();
        b.group(group = new NioEventLoopGroup()).channel(NioServerSocketChannel.class)
                .handler(new LoggingHandler(LogLevel.INFO))
                .childHandler(new DirectServerInitializer(sslCtx, enableCompression));
        b.bind(localAddress).sync().channel().closeFuture().sync();
    }

    public void closeClients() {
        checkNotClosed();
        if (group == null) {
            return;
        }

        group.shutdownGracefully();
        group = null;
    }
}
