package org.rx.socks.tcp;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.*;
import org.rx.socks.Sockets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

import static org.rx.core.Contract.*;

@Slf4j
@RequiredArgsConstructor
public class TcpServer<T extends SessionClient> extends Disposable implements EventTarget<TcpServer<T>> {
    public abstract static class BaseServerHandler<T extends SessionClient> extends ChannelInboundHandlerAdapter {
        protected static final Logger log = LoggerFactory.getLogger(BaseServerHandler.class);
        private WeakReference<TcpServer<T>> weakRef;
        private T client;

        protected TcpServer<T> getServer() {
            TcpServer<T> server = weakRef.get();
            require(server);
            return server;
        }

        protected T getClient() {
            require(client);
            return client;
        }

        public BaseServerHandler(TcpServer<T> server) {
            require(server);
            this.weakRef = new WeakReference<>(server);
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
//            super.channelActive(ctx);
            log.debug("channelActive {}", ctx.channel().remoteAddress());
            TcpServer<T> server = getServer();
            if (server.getClients().size() > server.getCapacity()) {
                log.warn("Not enough capacity");
                Sockets.closeOnFlushed(ctx.channel());
                return;
            }
            client = server.createClient(ctx);
            NEventArgs<T> args = new NEventArgs<>(client);
            server.raiseEvent(server.onConnected, args);
            if (args.isCancel()) {
                log.warn("Close client");
                Sockets.closeOnFlushed(ctx.channel());
            }
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
//            super.channelRead(ctx, msg);
            log.debug("channelRead {} {}", ctx.channel().remoteAddress(), msg.getClass());
            TcpServer<T> server = getServer();
            SessionPacket pack;
            if ((pack = as(msg, SessionPacket.class)) == null) {
                log.debug("channelRead discard {} {}", ctx.channel().remoteAddress(), msg.getClass());
                return;
            }
            server.raiseEvent(server.onReceive, new PackEventArgs<>(getClient(), pack));
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
//            super.channelInactive(ctx);
            log.debug("channelInactive {}", ctx.channel().remoteAddress());
            TcpServer<T> server = getServer();
            T client = getClient();
            try {
                server.raiseEvent(server.onDisconnected, new NEventArgs<>(client));
            } finally {
                server.removeClient(client);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
//            super.exceptionCaught(ctx, cause);
            log.error("serverCaught {}", ctx.channel().remoteAddress(), cause);
            TcpServer<T> server = getServer();
            ErrorEventArgs<T> args = new ErrorEventArgs<>(getClient(), cause);
            try {
                server.raiseEvent(server.onError, args);
            } catch (Exception e) {
                log.error("serverCaught", e);
            }
            if (args.isCancel()) {
                return;
            }
            Sockets.closeOnFlushed(ctx.channel());
        }
    }

    public volatile BiConsumer<TcpServer<T>, NEventArgs<T>> onConnected, onDisconnected;
    public volatile BiConsumer<TcpServer<T>, PackEventArgs<T>> onSend, onReceive;
    public volatile BiConsumer<TcpServer<T>, ErrorEventArgs<T>> onError;
    @Getter
    private final TcpConfig config;
    private final Class sessionClientType;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private SslContext sslCtx;
    @Getter
    private Map<SessionId, Set<T>> clients = new ConcurrentHashMap<>();
    @Getter
    private volatile boolean isStarted;
    @Getter
    @Setter
    private int capacity = 1000000;

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

        if (config.isEnableSsl()) {
            SelfSignedCertificate ssc = new SelfSignedCertificate();
            sslCtx = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();
        }
        bossGroup = Sockets.bossEventLoop();
        workerGroup = Sockets.workEventLoop();
        ServerBootstrap b = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(Sockets.getServerChannelClass())
                .option(ChannelOption.SO_REUSEADDR, true)
                .option(ChannelOption.SO_BACKLOG, 128)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, config.getConnectTimeout())
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.AUTO_READ, config.isAutoRead())
                .handler(new LoggingHandler(LogLevel.INFO))
                .childHandler(new TcpChannelInitializer(config, sslCtx == null ? null : channel -> sslCtx.newHandler(channel.alloc())));
        ChannelFuture f = b.bind(config.getEndpoint()).sync();
        isStarted = true;
        log.debug("Listened on port {}..", config.getEndpoint());

        if (waitClose) {
            f.channel().closeFuture().sync();
        }
    }

    protected T createClient(ChannelHandlerContext ctx) {
        return (T) Reflects.newInstance(isNull(sessionClientType, SessionClient.class), ctx);
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
