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
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.beans.DateTime;
import org.rx.common.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

import static org.rx.common.Contract.*;
import static org.rx.util.AsyncTask.TaskFactory;

@Slf4j
public class TcpServer<T extends TcpServer.ClientSession> extends Disposable {
    @RequiredArgsConstructor
    public static class ClientSession {
        @Getter
        private final SessionChannelId id;
        protected final transient ChannelHandlerContext channel;
        @Getter
        @Setter
        private Date connectedTime;
    }

    private class ServerInitializer extends ChannelInitializer<SocketChannel> {
        @Override
        public void initChannel(SocketChannel ch) {
            ChannelPipeline pipeline = ch.pipeline();

            if (sslCtx != null) {
                pipeline.addLast(sslCtx.newHandler(ch.alloc()));
            }
            // Enable stream compression (you can remove these two if unnecessary)
            pipeline.addLast(ZlibCodecFactory.newZlibEncoder(ZlibWrapper.GZIP));
            pipeline.addLast(ZlibCodecFactory.newZlibDecoder(ZlibWrapper.GZIP));

            ch.pipeline().addLast(new ObjectEncoder(),
                    new ObjectDecoder(ClassResolvers.weakCachingConcurrentResolver(this.getClass().getClassLoader())),
                    new ServerHandler());
        }
    }

    private class ServerHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            //Unpooled
            super.channelRead(ctx, msg);
            log.info("channelRead {} {}", ctx.channel().remoteAddress(), msg.getClass());
            if (SessionId.class.equals(msg.getClass())) {
                SessionChannelId sessionChannelId = new SessionChannelId(ctx.channel().id());
                sessionChannelId.sessionId((SessionId) msg);
                T client = createClient(sessionChannelId, ctx);
                NEventArgs<T> args = new NEventArgs<>(client);
                EventArgs.raiseEvent(onConnected, _this(), args);
                if (args.isCancel()) {
                    log.warn("Close client");
                    ctx.close();
                } else {
                    addClient(client);
                }
                return;
            }

            SessionPack pack;
            if ((pack = as(msg, SessionPack.class)) == null) {
                ctx.writeAndFlush(SessionPack.error("Error pack"));
                TaskFactory.scheduleOnce(ctx::close, 4 * 1000);
                return;
            }
            EventArgs.raiseEvent(onReceive, _this(), new PackEventArgs<>(findClient(ctx), pack));
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            super.channelActive(ctx);
            log.info("channelActive {}", ctx.channel().remoteAddress());

            if (clients.size() > maxClients) {
                log.warn("Not enough space");
                ctx.close();
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            super.channelInactive(ctx);
            log.info("channelInactive {}", ctx.channel().remoteAddress());

            T client = findClient(ctx);
            try {
                EventArgs.raiseEvent(onDisconnected, _this(), new NEventArgs<>(client));
            } finally {
                removeClient(ctx);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            super.exceptionCaught(ctx, cause);
            log.error("serverCaught {}", ctx.channel().remoteAddress(), cause);
            ErrorEventArgs<T> args = new ErrorEventArgs<>(findClient(ctx), cause);
            try {
                EventArgs.raiseEvent(onError, _this(), args);
            } catch (Exception e) {
                log.error("serverCaught", e);
            }
            if (!args.isCancel()) {
                ctx.close();
            }
        }
    }

    public volatile BiConsumer<TcpServer<T>, NEventArgs<T>> onConnected, onDisconnected;
    public volatile BiConsumer<TcpServer<T>, PackEventArgs<T>> onSend, onReceive;
    public volatile BiConsumer<TcpServer<T>, ErrorEventArgs<T>> onError;
    @Getter
    private int port;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private SslContext sslCtx;
    @Getter
    private volatile boolean isStarted;
    @Getter
    private final Map<SessionId, Set<T>> clients;
    @Getter
    @Setter
    private int maxClients;
    private Class clientSessionType;

    private TcpServer<T> _this() {
        return this;
    }

    public TcpServer(int port, boolean ssl) {
        this(port, ssl, null);
    }

    @SneakyThrows
    public TcpServer(int port, boolean ssl, Class clientSessionType) {
        clients = new ConcurrentHashMap<>();
        this.port = port;
        if (ssl) {
            SelfSignedCertificate ssc = new SelfSignedCertificate();
            sslCtx = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();
        }

        this.maxClients = 1000000;
        this.clientSessionType = clientSessionType == null ? TcpServer.ClientSession.class : clientSessionType;
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
        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
                .option(ChannelOption.SO_BACKLOG, 128)
                .option(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .channel(NioServerSocketChannel.class)
                .handler(new LoggingHandler(LogLevel.INFO))
                .childHandler(new ServerInitializer());

        ChannelFuture f = b.bind(port).sync();
        isStarted = true;
        log.info("Listened on port {}..", port);

        if (waitClose) {
            f.channel().closeFuture().sync();
        }
    }

    protected T createClient(SessionChannelId sessionChannelId, ChannelHandlerContext ctx) {
        T client = (T) App.newInstance(clientSessionType, sessionChannelId, ctx);
        client.setConnectedTime(DateTime.now());
        return client;
    }

    protected void addClient(T client) {
        clients.computeIfAbsent(client.getId().sessionId(), k -> Collections.synchronizedSet(new HashSet<>())).add(client);
    }

    protected T findClient(ChannelHandlerContext ctx) {
        return NQuery.of(clients.values()).selectMany(p -> p).where(p -> p.channel == ctx).single();
    }

    protected void removeClient(ChannelHandlerContext ctx) {
        NQuery.of(clients.values()).firstOrDefault(p -> p.removeIf(x -> x.channel == ctx));
    }

    public <TPack extends SessionPack> void send(SessionChannelId sessionChannelId, TPack pack) {
        checkNotClosed();
        require(sessionChannelId, pack);

        Set<T> set = clients.get(sessionChannelId.sessionId());
        if (set == null) {
            throw new InvalidOperationException(String.format("Client %s not found", sessionChannelId.getAppName()));
        }
        T client = NQuery.of(set).where(p -> eq(p.getId(), sessionChannelId)).firstOrDefault();
        if (client == null) {
            throw new InvalidOperationException(String.format("Client %s-%s not found", sessionChannelId.getAppName(), sessionChannelId.getChannelId()));
        }
        EventArgs.raiseEvent(onSend, this, new PackEventArgs<>(client, pack));
        client.channel.writeAndFlush(pack);
    }
}
