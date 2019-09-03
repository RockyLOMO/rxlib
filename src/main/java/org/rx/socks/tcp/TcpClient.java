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
import org.rx.core.*;
import org.rx.socks.Sockets;
import org.rx.util.ManualResetEvent;

import java.net.InetSocketAddress;
import java.util.concurrent.Future;
import java.util.function.BiConsumer;

import static org.rx.beans.$.$;
import static org.rx.core.Contract.require;
import static org.rx.util.AsyncTask.TaskFactory;

@Slf4j
public class TcpClient extends Disposable implements EventTarget<TcpClient> {
    public interface EventNames {
        String Error = "onError";
        String Connected = "onConnected";
        String Disconnected = "onDisconnected";
        String Send = "onSend";
        String Receive = "onReceive";
    }

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
            log.debug("clientRead {} {}", ctx.channel().remoteAddress(), msg.getClass());
            if (SessionId.class.equals(msg.getClass())) {
                NEventArgs<ChannelHandlerContext> args = new NEventArgs<>(ctx);
                raiseEvent(onConnected, args);
                if (args.isCancel()) {
                    ctx.close();
                } else {
                    isConnected = true;
                    waiter.set();
                }
                return;
            }

            SessionPack pack = (SessionPack) msg;
            if (!StringUtils.isEmpty(pack.getErrorMessage())) {
                exceptionCaught(ctx, new InvalidOperationException(String.format("Server error message: %s", pack.getErrorMessage())));
                return;
            }
            raiseEvent(onReceive, new PackEventArgs<>(ctx, pack));
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            super.channelActive(ctx);
            log.debug("clientActive {}", ctx.channel().remoteAddress());
            channel = ctx;

            ctx.writeAndFlush(sessionId);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            super.channelInactive(ctx);
            log.debug("clientInactive {}", ctx.channel().remoteAddress());
            isConnected = false;
            channel = null;

            NEventArgs<ChannelHandlerContext> args = new NEventArgs<>(ctx);
            raiseEvent(onDisconnected, args);
            if (args.isCancel()) {
                return;
            }
            reconnect();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            super.exceptionCaught(ctx, cause);
            log.error("clientCaught {}", ctx.channel().remoteAddress(), cause);
            ErrorEventArgs<ChannelHandlerContext> args = new ErrorEventArgs<>(ctx, cause);
            try {
                raiseEvent(onError, args);
            } catch (Exception e) {
                log.error("clientCaught", e);
            }
            if (args.isCancel()) {
                return;
            }
            ctx.close();
        }
    }

    public volatile BiConsumer<TcpClient, NEventArgs<ChannelHandlerContext>> onConnected, onDisconnected;
    public volatile BiConsumer<TcpClient, PackEventArgs<ChannelHandlerContext>> onSend, onReceive;
    public volatile BiConsumer<TcpClient, ErrorEventArgs<ChannelHandlerContext>> onError;
    private EventLoopGroup workerGroup;
    private SslContext sslCtx;
    private ChannelHandlerContext channel;
    @Getter
    private InetSocketAddress serverAddress;
    @Getter
    private volatile boolean isConnected;
    private ManualResetEvent waiter;
    @Getter
    @Setter
    private long connectTimeout;
    @Getter
    @Setter
    private volatile boolean autoReconnect;
    @Getter
    private SessionId sessionId;

    public TcpClient(String endpoint, boolean ssl) {
        this(Sockets.parseAddress(endpoint), ssl, null);
    }

    public TcpClient(InetSocketAddress endpoint, boolean ssl, SessionId sessionId) {
        init(endpoint, ssl, sessionId);
    }

    protected TcpClient() {
    }

    @SneakyThrows
    protected void init(InetSocketAddress endpoint, boolean ssl, SessionId sessionId) {
        require(endpoint);
        if (sessionId == null) {
            sessionId = SessionPack.defaultId;
        }

        serverAddress = endpoint;
        if (ssl) {
            sslCtx = SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build();
        }
        this.sessionId = sessionId;
        connectTimeout = TcpServer.defaultTimeout;
        waiter = new ManualResetEvent();
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
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) connectTimeout)
                .channel(NioSocketChannel.class)
                .handler(new ClientInitializer());

        b.connect(serverAddress).sync();
        if (wait) {
            waiter.waitOne(connectTimeout);
            waiter.reset();
        }
    }

    private void reconnect() {
        if (!autoReconnect) {
            return;
        }

        $<Future> $f = $();
        $f.$ = TaskFactory.schedule(() -> {
            App.catchCall(() -> connect());
            if (!autoReconnect || isConnected()) {
                $f.$.cancel(false);
            }
        }, 2 * 1000);
    }

    public <T extends SessionPack> void send(T pack) {
        require(pack, (Object) isConnected);

        PackEventArgs<ChannelHandlerContext> args = new PackEventArgs<>(channel, pack);
        raiseEvent(onSend, args);
        if (args.isCancel()) {
            return;
        }
        channel.writeAndFlush(pack);
    }
}
