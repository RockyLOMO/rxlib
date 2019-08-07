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
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.rx.beans.$;
import org.rx.common.*;
import org.rx.socks.Sockets;

import java.net.InetSocketAddress;
import java.util.concurrent.Future;
import java.util.function.BiConsumer;

import static org.rx.beans.$.$;
import static org.rx.common.Contract.require;
import static org.rx.util.AsyncTask.TaskFactory;

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
                exceptionCaught(ctx, new InvalidOperationException(String.format("Server error message: %s", socksPack.getErrorMessage())));
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
            if (autoReconnect) {
                reconnect();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            super.exceptionCaught(ctx, cause);
            log.error("clientCaught {}", ctx.channel().remoteAddress(), cause);
            ErrorEventArgs<ChannelHandlerContext> args = new ErrorEventArgs<>(ctx, cause);
            try {
                EventArgs.raiseEvent(onError, _this(), args);
            } catch (Exception e) {
                log.error("clientCaught", e);
            }
            if (!args.isCancel()) {
                ctx.close();
            }
        }
    }

    public volatile BiConsumer<TcpClient, NEventArgs<ChannelHandlerContext>> onConnected, onDisconnected;
    public volatile BiConsumer<TcpClient, PackEventArgs<ChannelHandlerContext>> onSend, onReceive;
    public volatile BiConsumer<TcpClient, ErrorEventArgs<ChannelHandlerContext>> onError;
    private InetSocketAddress serverAddress;
    private EventLoopGroup workerGroup;
    @Getter
    private volatile boolean isConnected;
    private SslContext sslCtx;
    private ChannelHandlerContext channel;
    @Getter
    @Setter
    private int connectTimeout;
    @Getter
    @Setter
    private volatile boolean autoReconnect;
    @Getter
    private SessionId sessionId;

    private TcpClient _this() {
        return this;
    }

    public TcpClient(String endPoint, boolean ssl) {
        this(endPoint, ssl, SessionPack.defaultId);
    }

    @SneakyThrows
    public TcpClient(String endPoint, boolean ssl, SessionId sessionId) {
        require();

        connectTimeout = 30 * 1000;
        serverAddress = Sockets.parseAddress(endPoint);
        if (ssl) {
            sslCtx = SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build();
        }
        this.sessionId = sessionId;
    }

    @Override
    protected void freeObjects() {
        autoReconnect = false; //import
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
            workerGroup = null;
        }
        isConnected = false;
    }

    public void connect() {
        connect(false);
    }

    @SneakyThrows
    public void connect(boolean wait) {
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
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeout)
                .channel(NioSocketChannel.class)
                .handler(new ClientInitializer());

        ChannelFuture sync = b.connect(serverAddress).sync();
        if (wait) {
            sync.await().sync();
        }
    }

    private void reconnect() {
        $<Future> $f = $();
        $f.$ = TaskFactory.schedule(() -> {
            App.catchCall(() -> connect());
            if (isConnected()) {
                $f.$.cancel(false);
            }
        }, 2 * 1000);
    }

    public <T extends SessionPack> void send(T pack) {
        require(pack, isConnected);

        EventArgs.raiseEvent(onSend, this, new PackEventArgs<>(channel, pack));
        channel.writeAndFlush(pack);
    }
}
