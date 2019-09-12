package org.rx.socks.tcp;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
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

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

import static org.rx.core.Contract.*;

@Slf4j
public class TcpServer<T extends SessionClient> extends Disposable implements EventTarget<TcpServer<T>> {
    public volatile BiConsumer<TcpServer<T>, NEventArgs<T>> onConnected, onDisconnected;
    public volatile BiConsumer<TcpServer<T>, PackEventArgs<T>> onSend, onReceive;
    public volatile BiConsumer<TcpServer<T>, ErrorEventArgs<T>> onError;
    @Getter
    private int port;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private SslContext sslCtx;
    private boolean enableCompress;
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

        this.maxClients = 1000000;
        this.sessionClientType = sessionClientType == null ? SessionClient.class : sessionClientType;
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
        setBootOptions(b);
        ChannelFuture f = b.bind(port).sync();
        isStarted = true;
        log.debug("Listened on port {}..", port);

        if (waitClose) {
            f.channel().closeFuture().sync();
        }
    }

    protected void setBootOptions(ServerBootstrap b) {
    }

    protected T createClient(ChannelHandlerContext ctx) {
        return (T) Reflects.newInstance(sessionClientType, ctx);
    }

    protected void addClient(SessionId sessionId, T client) {
        clients.computeIfAbsent(sessionId, k -> Collections.synchronizedSet(new HashSet<>())).add(client);
    }

//    protected T findClient(ChannelId channelId, boolean throwOnEmpty) {
//        T client = NQuery.of(clients.values()).selectMany(p -> p).where(p -> p.getId() == channelId).singleOrDefault();
//        if (client == null && throwOnEmpty) {
//            throw new InvalidOperationException(String.format("Client %s not found", channelId));
//        }
//        return client;
//    }
//
//    protected void removeClient(ChannelHandlerContext ctx) {
//        for (Map.Entry<SessionId, Set<T>> entry : clients.entrySet()) {
//            if (!entry.getValue().removeIf(x -> x.channel == ctx)) {
//                continue;
//            }
//            if (entry.getValue().isEmpty()) {
//                clients.remove(entry.getKey());
//            }
//            break;
//        }
//    }

    public <TPack extends SessionPacket> void send(ChannelId sessionClientId, TPack pack) {
        checkNotClosed();
        require(sessionClientId, pack);

        Set<T> set = clients.get(pack.getSessionId());
        if (set == null) {
            throw new InvalidOperationException(String.format("Session %s not found", pack.getSessionId()));
        }
        T client = NQuery.of(set).where(p -> eq(p.getId(), sessionClientId)).singleOrDefault();
        if (client == null) {
            throw new InvalidOperationException(String.format("Client %s not found", sessionClientId));
        }
        PackEventArgs<T> args = new PackEventArgs<>(client, pack);
        raiseEvent(onSend, args);
        if (args.isCancel()) {
            return;
        }
        client.channel.writeAndFlush(pack);
    }
}
