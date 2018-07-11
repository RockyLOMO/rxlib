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
import org.rx.App;
import org.rx.Disposable;
import org.rx.socks.Sockets;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

import static org.rx.Contract.require;

public final class ProxyServer extends Disposable {
    public static final String Compression_Key = "app.netProxy.compression";
    private EventLoopGroup     group;
    private boolean            enableSsl;

    public boolean isEnableSsl() {
        return enableSsl;
    }

    public void setEnableSsl(boolean enableSsl) {
        this.enableSsl = enableSsl;
    }

    public boolean isEnableCompression() {
        return App.convert(App.readSetting(Compression_Key), boolean.class);
    }

    public boolean isBusy() {
        return group != null;
    }

    @Override
    protected void freeUnmanaged() {
        if (group != null) {
            group.shutdownGracefully();
        }
    }

    public void start(int localPort, SocketAddress directAddress) {
        start(new InetSocketAddress(Sockets.AnyAddress, localPort), directAddress);
    }

    @SneakyThrows
    public void start(SocketAddress localAddress, SocketAddress directAddress) {
        checkNotClosed();
        require(group == null);
        require(localAddress);

        // Configure SSL.
        SslContext sslCtx = null;
        if (enableSsl) {
            SelfSignedCertificate ssc = new SelfSignedCertificate();
            sslCtx = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();
        }

        ServerBootstrap b = new ServerBootstrap();
        SslContext ssl = sslCtx;
        b.group(group = new NioEventLoopGroup()).channel(NioServerSocketChannel.class)
                .handler(new LoggingHandler(LogLevel.INFO)).childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline pipeline = ch.pipeline();
                        if (ssl != null) {
                            pipeline.addLast(ssl.newHandler(ch.alloc()));
                        }
                        if (isEnableCompression()) {
                            // Enable stream compression (you can remove these two if unnecessary)
                            pipeline.addLast(ZlibCodecFactory.newZlibEncoder(ZlibWrapper.GZIP));
                            pipeline.addLast(ZlibCodecFactory.newZlibDecoder(ZlibWrapper.GZIP));
                        }

                        // Add the number codec first,
                        pipeline.addLast(new ByteArrayDecoder());
                        pipeline.addLast(new ByteArrayEncoder());

                        // and then business logic.
                        // Please note we create a handler for every new channel because it has stateful properties.
                        pipeline.addLast(new DirectServerHandler(enableSsl, directAddress));
                    }
                });
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
