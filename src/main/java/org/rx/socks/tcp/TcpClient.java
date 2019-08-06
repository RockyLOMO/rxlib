package org.rx.socks.tcp;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.compression.ZlibCodecFactory;
import io.netty.handler.codec.compression.ZlibWrapper;
import io.netty.handler.codec.serialization.ClassResolvers;
import io.netty.handler.codec.serialization.ObjectDecoder;
import io.netty.handler.codec.serialization.ObjectEncoder;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.rx.common.Disposable;
import org.rx.common.EventArgs;
import org.rx.common.InvalidOperationException;
import org.rx.common.NEventArgs;
import org.rx.socks.Sockets;

import java.net.InetSocketAddress;
import java.util.function.BiConsumer;

import static org.rx.common.Contract.require;

@Slf4j
public class TcpClient extends Disposable {
    private class ClientInitializer extends ChannelInitializer<SocketChannel> {
        @Override
        public void initChannel(SocketChannel ch) {
            ChannelPipeline pipeline = ch.pipeline();

            if (sslCtx != null) {
                pipeline.addLast(sslCtx.newHandler(ch.alloc(), serverAddress.getHostString(), serverAddress.getPort()));
            }
            // Enable stream compression (you can remove these two if unnecessary)
            pipeline.addLast(ZlibCodecFactory.newZlibEncoder(ZlibWrapper.GZIP));
            pipeline.addLast(ZlibCodecFactory.newZlibDecoder(ZlibWrapper.GZIP));

            pipeline.addLast(new ObjectEncoder(),
                    new ObjectDecoder(ClassResolvers.weakCachingConcurrentResolver(this.getClass().getClassLoader())),
                    new ClientHandler());
        }
    }

    private class ClientHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            super.channelRead(ctx, msg);
            log.info("clientRead {} {}", ctx.channel().remoteAddress(), msg.getClass());
            SessionPack socksPack = (SessionPack) msg;
            if (!StringUtils.isEmpty(socksPack.getErrorMessage())) {
                log.error("ErrorMessage from server: {}", socksPack.getErrorMessage());
                close();
                return;
            }
            EventArgs.raiseEvent(onReceive, _this(), new PackEventArgs<>(ctx, socksPack));
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            super.channelActive(ctx);
            log.info("clientActive {}", ctx.channel().remoteAddress());
            isConnected = true;
            channel = ctx;

            ctx.writeAndFlush(sessionId);
            EventArgs.raiseEvent(onConnected, _this(), new NEventArgs<>(ctx));
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            super.channelInactive(ctx);
            log.info("clientInactive {}", ctx.channel().remoteAddress());
            isConnected = false;
            channel = null;

            EventArgs.raiseEvent(onDisconnected, _this(), new NEventArgs<>(ctx));
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            super.exceptionCaught(ctx, cause);
            log.error("clientCaught {}", ctx.channel().remoteAddress(), cause);
            ErrorEventArgs args = new ErrorEventArgs(cause.toString());
            EventArgs.raiseEvent(onError, _this(), args);
            if (!args.isCancel()) {
                ctx.close();
            }
        }
    }

    public volatile BiConsumer<TcpClient, NEventArgs<ChannelHandlerContext>> onConnected, onDisconnected;
    public volatile BiConsumer<TcpClient, PackEventArgs<ChannelHandlerContext>> onSend, onReceive;
    public volatile BiConsumer<TcpClient, ErrorEventArgs> onError;
    private InetSocketAddress serverAddress;
    private EventLoopGroup workerGroup;
    @Getter
    private volatile boolean isConnected;
    private SslContext sslCtx;
    private ChannelHandlerContext channel;
    @Getter
    private SessionId sessionId;

    private TcpClient _this() {
        return this;
    }

    @SneakyThrows
    public TcpClient(String endPoint, boolean ssl, SessionId sessionId) {
        require();

        serverAddress = Sockets.parseAddress(endPoint);
        if (ssl) {
            sslCtx = SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build();
        }
        this.sessionId = sessionId;
    }

    @Override
    protected void freeObjects() {
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
            workerGroup = null;
        }
        isConnected = false;
    }

    @SneakyThrows
    public void connect(int timeout) {
        if (isConnected) {
            throw new InvalidOperationException("Client has connected");
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }

        workerGroup = new NioEventLoopGroup();
        Bootstrap b = new Bootstrap();
        b.group(workerGroup)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, timeout)
                .channel(NioSocketChannel.class)
                .handler(new ClientInitializer());

        b.connect(serverAddress).sync();
    }

    public <T extends SessionPack> void send(T pack) {
        require(pack, isConnected);

        EventArgs.raiseEvent(onSend, this, new PackEventArgs<>(channel, pack));
        channel.writeAndFlush(pack);
    }
}
