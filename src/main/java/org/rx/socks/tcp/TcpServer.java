package org.rx.socks.tcp;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.compression.ZlibCodecFactory;
import io.netty.handler.codec.compression.ZlibWrapper;
import io.netty.handler.codec.serialization.ClassResolvers;
import io.netty.handler.codec.serialization.ObjectDecoder;
import io.netty.handler.codec.serialization.ObjectEncoder;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

import static org.rx.core.Contract.*;

@Slf4j
public class TcpServer<T extends SessionClient> extends Disposable implements EventTarget<TcpServer<T>> {
    private class ServerInitializer extends ChannelInitializer<SocketChannel> {
        @Override
        public void initChannel(SocketChannel ch) {
            ChannelPipeline pipeline = ch.pipeline();
            if (sslCtx != null) {
                pipeline.addLast(sslCtx.newHandler(ch.alloc()));
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
            channelHandlers = null;  //loop ref
        }
    }

    public abstract static class BaseServerHandler<T extends SessionClient> extends ChannelInboundHandlerAdapter {
        protected static final Logger log = LoggerFactory.getLogger(BaseServerHandler.class);
        protected final TcpServer<T> server;

        public BaseServerHandler(TcpServer<T> server) {
            require(server);
            this.server = server;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            super.channelRead(ctx, msg);
            log.debug("channelRead {} {}", ctx.channel().remoteAddress(), msg.getClass());
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            super.channelActive(ctx);
            log.debug("channelActive {}", ctx.channel().remoteAddress());

            if (server.getClients().size() > server.getMaxClients()) {
                log.warn("Not enough space");
                ctx.close();
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            super.channelInactive(ctx);
            log.debug("channelInactive {}", ctx.channel().remoteAddress());

            T client = server.findClient(ctx.channel().id());
            try {
                server.raiseEvent(server.onDisconnected, new NEventArgs<>(client));
            } finally {
                server.removeClient(client);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            super.exceptionCaught(ctx, cause);
            log.error("serverCaught {}", ctx.channel().remoteAddress(), cause);

            ErrorEventArgs<T> args = new ErrorEventArgs<>(server.findClient(ctx.channel().id()), cause);
            try {
                server.raiseEvent(server.onError, args);
            } catch (Exception e) {
                log.error("serverCaught", e);
            }
            if (args.isCancel()) {
                return;
            }
            ctx.close();
        }
    }

    public static <T extends SessionClient> TcpServer<T> newPacketServer(int port, Class sessionClientType) {
        TcpServer<T> server = new TcpServer<>(port, true, true, sessionClientType);
        server.setChannelHandlers(new ObjectEncoder(),
                new ObjectDecoder(ClassResolvers.weakCachingConcurrentResolver(TcpServer.class.getClassLoader())),
                new PacketServerHandler<>(server));
        return server;
    }

    public volatile BiConsumer<TcpServer<T>, NEventArgs<T>> onConnected, onDisconnected;
    public volatile BiConsumer<TcpServer<T>, PackEventArgs<T>> onSend, onReceive;
    public volatile BiConsumer<TcpServer<T>, ErrorEventArgs<T>> onError;
    @Getter
    private int port;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private SslContext sslCtx;
    private boolean enableCompress;
    private ChannelHandler[] channelHandlers;
    @Getter
    private volatile boolean isStarted;
    @Getter
    private final Map<SessionId, Set<T>> clients;
    private Class sessionClientType;
    @Getter
    @Setter
    private int maxClients;
    @Getter
    @Setter
    private long connectTimeout;

    @SneakyThrows
    public TcpServer(int port, boolean ssl, boolean enableCompress, Class sessionClientType) {
        clients = new ConcurrentHashMap<>();
        this.port = port;
        if (ssl) {
            SelfSignedCertificate ssc = new SelfSignedCertificate();
            sslCtx = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();
        }
        this.enableCompress = enableCompress;
        this.sessionClientType = sessionClientType == null ? SessionClient.class : sessionClientType;

        this.maxClients = 1000000;
        this.connectTimeout = config.getDefaultSocksTimeout();
    }

    @Override
    protected void freeObjects() {
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
            workerGroup = null;
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
            bossGroup = null;
        }
        isStarted = false;
    }

    public void start() {
        start(false);
    }

    @SneakyThrows
    public void start(boolean waitClose) {
        if (isStarted) {
            throw new InvalidOperationException("Server has started");
        }

        bossGroup = new NioEventLoopGroup();
        workerGroup = new NioEventLoopGroup();
//        bossGroup = new NioEventLoopGroup(1, TaskFactory.getExecutor());
//        workerGroup = new NioEventLoopGroup(0, TaskFactory.getExecutor());
        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
                .option(ChannelOption.SO_REUSEADDR, true)
                .option(ChannelOption.SO_BACKLOG, 128)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) connectTimeout)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .channel(NioServerSocketChannel.class)
                .handler(new LoggingHandler(LogLevel.INFO))
                .childHandler(new ServerInitializer());
        ChannelFuture f = b.bind(port).sync();
        isStarted = true;
        log.debug("Listened on port {}..", port);

        if (waitClose) {
            f.channel().closeFuture().sync();
        }
    }

    public TcpServer<T> setChannelHandlers(ChannelHandler... channelHandlers) {
        this.channelHandlers = channelHandlers;
        return this;
    }

    protected T createClient(ChannelHandlerContext ctx) {
        return (T) Reflects.newInstance(sessionClientType, ctx);
    }

    protected void addClient(SessionId sessionId, T client) {
        clients.computeIfAbsent(sessionId, k -> Collections.synchronizedSet(new HashSet<>())).add(client);
    }

    protected T findClient(ChannelId channelId) {
        T client = NQuery.of(clients.values()).selectMany(p -> p).where(p -> p.getId().equals(channelId)).singleOrDefault();
        if (client == null) {
            throw new InvalidOperationException(String.format("Client %s not found", channelId));
        }
        return client;
    }

    protected void removeClient(T client) {
        for (Map.Entry<SessionId, Set<T>> entry : clients.entrySet()) {
            if (!entry.getValue().removeIf(x -> x == client)) {
                continue;
            }
            if (entry.getValue().isEmpty()) {
                clients.remove(entry.getKey());
            }
            break;
        }
    }

    public <TPack extends SessionPacket> void send(ChannelId sessionClientId, TPack pack) {
        checkNotClosed();
        require(sessionClientId, pack);

        T client = findClient(sessionClientId);
        PackEventArgs<T> args = new PackEventArgs<>(client, pack);
        raiseEvent(onSend, args);
        if (args.isCancel()) {
            return;
        }
        client.channel.writeAndFlush(pack);
    }
}
