package org.rx.socks.proxy;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.bytes.ByteArrayDecoder;
import io.netty.handler.codec.bytes.ByteArrayEncoder;
import io.netty.handler.codec.compression.ZlibCodecFactory;
import io.netty.handler.codec.compression.ZlibWrapper;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import lombok.SneakyThrows;
import org.rx.Disposable;

import java.net.InetSocketAddress;
import java.util.function.BiConsumer;

import static org.rx.Contract.require;

public class ProxyClient extends Disposable {
    private static class DirectClientInitializer extends ChannelInitializer<SocketChannel> {
        private final SslContext                          sslCtx;
        private boolean                                   enableCompression;
        private InetSocketAddress                         remoteAddress;
        private BiConsumer<ChannelHandlerContext, byte[]> onReceive;

        public DirectClientInitializer(SslContext sslCtx, boolean enableCompression, InetSocketAddress remoteAddress,
                                       BiConsumer<ChannelHandlerContext, byte[]> onReceive) {
            require(remoteAddress);

            this.sslCtx = sslCtx;
            this.enableCompression = enableCompression;
            this.remoteAddress = remoteAddress;
            this.onReceive = onReceive;
        }

        @Override
        public void initChannel(SocketChannel ch) {
            ChannelPipeline pipeline = ch.pipeline();
            if (sslCtx != null) {
                pipeline.addLast(sslCtx.newHandler(ch.alloc(), remoteAddress.getHostName(), remoteAddress.getPort()));
            }
            if (enableCompression) {
                pipeline.addLast(ZlibCodecFactory.newZlibEncoder(ZlibWrapper.GZIP));
                pipeline.addLast(ZlibCodecFactory.newZlibDecoder(ZlibWrapper.GZIP));
            }

            pipeline.addLast(new ByteArrayDecoder());
            pipeline.addLast(new ByteArrayEncoder());

            pipeline.addLast(new DirectClientHandler(onReceive));
        }
    }

    private EventLoopGroup      group;
    private boolean             enableSsl, enableCompression;
    private DirectClientHandler handler;

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

    public ProxyClient() {
        enableCompression = true;
    }

    @Override
    protected void freeUnmanaged() {
        if (group != null) {
            group.shutdownGracefully();
        }
    }

    public void connect(InetSocketAddress remoteAddress) {
        connect(remoteAddress, null);
    }

    @SneakyThrows
    public void connect(InetSocketAddress remoteAddress, BiConsumer<ChannelHandlerContext, byte[]> onReceive) {
        checkNotClosed();
        require(group == null);
        require(remoteAddress);

        // Configure SSL.
        SslContext sslCtx = null;
        if (enableSsl) {
            sslCtx = SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build();
        }

        Bootstrap b = new Bootstrap();
        b.group(group = new NioEventLoopGroup()).channel(NioSocketChannel.class)
                .handler(new DirectClientInitializer(sslCtx, enableCompression, remoteAddress, onReceive));
        ChannelFuture f = b.connect(remoteAddress).sync();
        handler = (DirectClientHandler) f.channel().pipeline().last();
    }

    public ChannelFuture send(byte[] bytes) {
        checkNotClosed();
        require(group != null);
        require(bytes);

        return handler.send(bytes);
    }
}
