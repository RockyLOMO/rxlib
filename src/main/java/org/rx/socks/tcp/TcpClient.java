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
import org.rx.beans.$;
import org.rx.core.*;
import org.rx.core.ManualResetEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.concurrent.Future;
import java.util.function.BiConsumer;

import static org.rx.beans.$.$;
import static org.rx.core.Contract.require;
import static org.rx.core.AsyncTask.TaskFactory;

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
                pipeline.addLast(sslCtx.newHandler(ch.alloc(), serverEndpoint.getHostString(), serverEndpoint.getPort()));
            }
            if (enableCompress) {
                pipeline.addLast(ZlibCodecFactory.newZlibEncoder(ZlibWrapper.GZIP));
                pipeline.addLast(ZlibCodecFactory.newZlibDecoder(ZlibWrapper.GZIP));
            }

            if (Arrays.isEmpty(channelHandlers)) {
                log.warn("Empty channel handlers");
                return;
            }
            pipeline.addLast(channelHandlers);
            channelHandlers = null;
        }
    }

    public static class BaseClientHandler extends ChannelInboundHandlerAdapter {
        protected static final Logger log = LoggerFactory.getLogger(TcpClient.class);
        protected final TcpClient client;

        public BaseClientHandler(TcpClient client) {
            require(client);
            this.client = client;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            super.channelRead(ctx, msg);
            log.debug("clientRead {} {}", ctx.channel().remoteAddress(), msg.getClass());
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            super.channelActive(ctx);
            log.debug("clientActive {}", ctx.channel().remoteAddress());
            client.channel = ctx;
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            super.channelInactive(ctx);
            log.debug("clientInactive {}", ctx.channel().remoteAddress());
            client.channel = null;
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            super.exceptionCaught(ctx, cause);
            log.error("clientCaught {}", ctx.channel().remoteAddress(), cause);
            ErrorEventArgs<ChannelHandlerContext> args = new ErrorEventArgs<>(ctx, cause);
            try {
                client.raiseEvent(client.onError, args);
            } catch (Exception e) {
                log.error("clientCaught", e);
            }
            if (args.isCancel()) {
                return;
            }
            ctx.close();
        }
    }

    public static TcpClient newPacketClient(InetSocketAddress serverEndpoint, SessionId sessionId) {
        TcpClient client = new TcpClient(serverEndpoint, true, true, sessionId);
        client.setChannelHandlers(new ObjectEncoder(),
                new ObjectDecoder(ClassResolvers.weakCachingConcurrentResolver(TcpClient.class.getClassLoader())),
                new PacketClientHandler(client));
        return client;
    }

    public volatile BiConsumer<TcpClient, NEventArgs<ChannelHandlerContext>> onConnected, onDisconnected;
    public volatile BiConsumer<TcpClient, PackEventArgs<ChannelHandlerContext>> onSend, onReceive;
    public volatile BiConsumer<TcpClient, ErrorEventArgs<ChannelHandlerContext>> onError;
    @Getter
    private InetSocketAddress serverEndpoint;
    private EventLoopGroup workerGroup;
    private SslContext sslCtx;
    private boolean enableCompress;
    private ChannelHandler[] channelHandlers;
    private ChannelHandlerContext channel;
    @Getter
    protected SessionId sessionId;
    @Getter
    protected volatile boolean isConnected;
    protected ManualResetEvent connectWaiter;
    @Getter
    @Setter
    private long connectTimeout;
    @Getter
    @Setter
    private volatile boolean autoReconnect;

    public TcpClient(InetSocketAddress serverEndpoint, boolean ssl, boolean enableCompress, SessionId sessionId) {
        init(serverEndpoint, ssl, enableCompress, sessionId);
    }

    protected TcpClient() {
    }

    @SneakyThrows
    protected void init(InetSocketAddress serverEndpoint, boolean ssl, boolean enableCompress, SessionId sessionId) {
        require(serverEndpoint, sessionId);

        this.serverEndpoint = serverEndpoint;
        if (ssl) {
            sslCtx = SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build();
        }
        this.enableCompress = enableCompress;
        this.sessionId = sessionId;
        connectTimeout = Contract.config.getDefaultSocksTimeout();
        connectWaiter = new ManualResetEvent();
    }

    public TcpClient setChannelHandlers(ChannelHandler... channelHandlers) {
        this.channelHandlers = channelHandlers;
        return this;
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

        b.connect(serverEndpoint).sync();
        if (wait) {
            connectWaiter.waitOne(connectTimeout);
            connectWaiter.reset();
        }
    }

    protected void reconnect() {
        if (!autoReconnect) {
            return;
        }

        $<Future> $f = $();
        $f.v = TaskFactory.schedule(() -> {
            App.catchCall(() -> connect());
            if (!autoReconnect || isConnected()) {
                $f.v.cancel(false);
            }
        }, 2 * 1000);
    }

    public <T extends SessionPacket> void send(T pack) {
        require(pack, (Object) isConnected);

        PackEventArgs<ChannelHandlerContext> args = new PackEventArgs<>(channel, pack);
        raiseEvent(onSend, args);
        if (args.isCancel()) {
            return;
        }
        channel.writeAndFlush(pack);
    }
}
