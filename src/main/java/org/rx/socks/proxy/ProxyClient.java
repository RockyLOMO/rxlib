package org.rx.socks.proxy;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
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
import org.rx.App;
import org.rx.Disposable;

import java.net.InetSocketAddress;
import java.util.function.BiConsumer;

import static org.rx.Contract.require;
import static org.rx.socks.proxy.ProxyServer.Compression_Key;

public class ProxyClient extends Disposable {
    private EventLoopGroup      group;
    private boolean             enableSsl;
    private DirectClientHandler handler;

    public boolean isEnableSsl() {
        return enableSsl;
    }

    public void setEnableSsl(boolean enableSsl) {
        this.enableSsl = enableSsl;
    }

    public boolean isEnableCompression() {
        return App.convert(App.readSetting(Compression_Key), boolean.class);
    }

    public boolean isConnected() {
        return !super.isClosed() && handler != null && handler.isConnected();
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
        SslContext ssl = sslCtx;
        b.group(group = new NioEventLoopGroup()).channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline pipeline = ch.pipeline();
                        if (ssl != null) {
                            pipeline.addLast(
                                    ssl.newHandler(ch.alloc(), remoteAddress.getHostName(), remoteAddress.getPort()));
                        }
                        if (isEnableCompression()) {
                            pipeline.addLast(ZlibCodecFactory.newZlibEncoder(ZlibWrapper.GZIP));
                            pipeline.addLast(ZlibCodecFactory.newZlibDecoder(ZlibWrapper.GZIP));
                        }

                        pipeline.addLast(new ByteArrayDecoder());
                        pipeline.addLast(new ByteArrayEncoder());

                        pipeline.addLast(new DirectClientHandler(onReceive));
                    }
                });
        ChannelFuture f = b.connect(remoteAddress).sync();
        handler = (DirectClientHandler) f.channel().pipeline().last();
    }

    public ChannelFuture send(byte[] bytes) {
        checkNotClosed();
        require(group != null);
        require(bytes);

        return handler.send(bytes);
    }

    public ChannelFuture send(ByteBuf bytes) {
        checkNotClosed();
        require(group != null);
        require(bytes);

        return handler.send(bytes);
    }
}
