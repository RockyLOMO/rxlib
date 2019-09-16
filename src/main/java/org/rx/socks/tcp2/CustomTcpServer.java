//package org.rx.socks.tcp2;
//
//import io.netty.bootstrap.ServerBootstrap;
//import io.netty.channel.*;
//import io.netty.channel.nio.NioEventLoopGroup;
//import io.netty.channel.socket.SocketChannel;
//import io.netty.channel.socket.nio.NioServerSocketChannel;
//import io.netty.handler.codec.compression.ZlibCodecFactory;
//import io.netty.handler.codec.compression.ZlibWrapper;
//import io.netty.handler.codec.serialization.ClassResolvers;
//import io.netty.handler.codec.serialization.ObjectDecoder;
//import io.netty.handler.codec.serialization.ObjectEncoder;
//import io.netty.handler.logging.LogLevel;
//import io.netty.handler.logging.LoggingHandler;
//import io.netty.handler.ssl.SslContext;
//import io.netty.handler.ssl.SslContextBuilder;
//import io.netty.handler.ssl.util.SelfSignedCertificate;
//import lombok.Getter;
//import lombok.RequiredArgsConstructor;
//import lombok.Setter;
//import lombok.SneakyThrows;
//import lombok.extern.slf4j.Slf4j;
//import org.rx.beans.DateTime;
//import org.rx.core.*;
//
//import java.util.*;
//import java.util.Collections;
//import java.util.concurrent.ConcurrentHashMap;
//import java.util.function.BiConsumer;
//
//import static org.rx.core.Contract.*;
//import static org.rx.core.AsyncTask.TaskFactory;
//
//@Slf4j
//public class CustomTcpServer<T extends CustomTcpServer.ClientSession> extends Disposable implements EventTarget<CustomTcpServer<T>> {
//    @RequiredArgsConstructor
//    public static class ClientSession {
//        @Getter
//        private final SessionChannelId id;
//        protected final transient ChannelHandlerContext channel;
//        @Getter
//        @Setter
//        private Date connectedTime;
//    }
//
//    private class PacketServerHandler extends ChannelInitializer<SocketChannel> {
//        @Override
//        public void initChannel(SocketChannel ch) {
//            ChannelPipeline pipeline = ch.pipeline();
//            if (sslCtx != null) {
//                pipeline.addLast(sslCtx.newHandler(ch.alloc()));
//            }
//            // Enable stream compression (you can remove these two if unnecessary)
//            pipeline.addLast(ZlibCodecFactory.newZlibEncoder(ZlibWrapper.GZIP));
//            pipeline.addLast(ZlibCodecFactory.newZlibDecoder(ZlibWrapper.GZIP));
//
////            pipeline.addLast(new ReadTimeoutHandler(readTimeout, TimeUnit.MILLISECONDS));
////            pipeline.addLast(new WriteTimeoutHandler(writeTimeout, TimeUnit.MILLISECONDS));
//            pipeline.addLast(new ObjectEncoder(),
//                    new ObjectDecoder(ClassResolvers.weakCachingConcurrentResolver(this.getClass().getClassLoader())),
//                    new ServerHandler());
//        }
//    }
//
//    private class ServerHandler extends ChannelInboundHandlerAdapter {
//        @Override
//        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
//            //Unpooled
//            super.channelRead(ctx, msg);
//            log.debug("channelRead {} {}", ctx.channel().remoteAddress(), msg.getClass());
//            if (SessionId.class.equals(msg.getClass())) {
//                SessionChannelId sessionChannelId = new SessionChannelId(ctx.channel().id());
//                sessionChannelId.sessionId((SessionId) msg);
//                T client = createClient(sessionChannelId, ctx);
//                NEventArgs<T> args = new NEventArgs<>(client);
//                raiseEvent(onConnected, args);
//                if (args.isCancel()) {
//                    log.warn("Close client");
//                    ctx.close();
//                } else {
//                    addClient(client);
//                    ctx.writeAndFlush(msg);
//                }
//                return;
//            }
//
//            AppSessionPacket pack;
//            if ((pack = as(msg, AppSessionPacket.class)) == null) {
//                ctx.writeAndFlush(AppSessionPacket.error("Error pack"));
//                TaskFactory.scheduleOnce(ctx::close, 4 * 1000);
//                return;
//            }
//            //异步避免阻塞
//            TaskFactory.run(() -> raiseEvent(onReceive, new PackEventArgs<>(findClient(ctx), pack)));
//        }
//
//        @Override
//        public void channelActive(ChannelHandlerContext ctx) throws Exception {
//            super.channelActive(ctx);
//            log.debug("channelActive {}", ctx.channel().remoteAddress());
//
//            if (clients.size() > maxClients) {
//                log.warn("Not enough space");
//                ctx.close();
//            }
//        }
//
//        @Override
//        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
//            super.channelInactive(ctx);
//            log.debug("channelInactive {}", ctx.channel().remoteAddress());
//
//            T client = findClient(ctx);
//            try {
//                raiseEvent(onDisconnected, new NEventArgs<>(client));
//            } finally {
//                removeClient(ctx);
//            }
//        }
//
//        @Override
//        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
//            super.exceptionCaught(ctx, cause);
//            log.error("serverCaught {}", ctx.channel().remoteAddress(), cause);
//            ErrorEventArgs<T> args = new ErrorEventArgs<>(findClient(ctx), cause);
////            if (cause instanceof ReadTimeoutException || cause instanceof WriteTimeoutException) {
////                args.setCancel(true);
////            }
//            try {
//                raiseEvent(onError, args);
//            } catch (Exception e) {
//                log.error("serverCaught", e);
//            }
//            if (args.isCancel()) {
//                return;
//            }
//            ctx.close();
//        }
//    }
//
//    public volatile BiConsumer<CustomTcpServer<T>, NEventArgs<T>> onConnected, onDisconnected;
//    public volatile BiConsumer<CustomTcpServer<T>, PackEventArgs<T>> onSend, onReceive;
//    public volatile BiConsumer<CustomTcpServer<T>, ErrorEventArgs<T>> onError;
//    @Getter
//    private int port;
//    private EventLoopGroup bossGroup;
//    private EventLoopGroup workerGroup;
//    private SslContext sslCtx;
//    @Getter
//    private volatile boolean isStarted;
//    @Getter
//    private final Map<SessionId, Set<T>> clients;
//    @Getter
//    @Setter
//    private int maxClients;
//    private Class clientSessionType;
//    @Getter
//    @Setter
//    private long connectTimeout;
////    private long readTimeout, writeTimeout;
//
//    public CustomTcpServer(int port, boolean ssl) {
//        this(port, ssl, null);
//    }
//
//    @SneakyThrows
//    public CustomTcpServer(int port, boolean ssl, Class clientSessionType) {
//        clients = new ConcurrentHashMap<>();
//        this.port = port;
//        if (ssl) {
//            SelfSignedCertificate ssc = new SelfSignedCertificate();
//            sslCtx = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();
//        }
//
//        this.maxClients = 1000000;
//        this.clientSessionType = clientSessionType == null ? CustomTcpServer.ClientSession.class : clientSessionType;
//        this.connectTimeout = config.getDefaultSocksTimeout();
////        this.writeTimeout = this.readTimeout = defaultTimeout * 2;
//    }
//
//    @Override
//    protected void freeObjects() {
//        if (workerGroup != null) {
//            workerGroup.shutdownGracefully();
//            workerGroup = null;
//        }
//        if (bossGroup != null) {
//            bossGroup.shutdownGracefully();
//            bossGroup = null;
//        }
//        isStarted = false;
//    }
//
//    public void start() {
//        start(false);
//    }
//
//    @SneakyThrows
//    public void start(boolean waitClose) {
//        if (isStarted) {
//            throw new InvalidOperationException("Server has started");
//        }
//
//        bossGroup = new NioEventLoopGroup();
//        workerGroup = new NioEventLoopGroup();
////        bossGroup = new NioEventLoopGroup(1, TaskFactory.getExecutor());
////        workerGroup = new NioEventLoopGroup(0, TaskFactory.getExecutor());
//        ServerBootstrap b = new ServerBootstrap();
//        b.group(bossGroup, workerGroup)
//                .option(ChannelOption.SO_REUSEADDR, true)
//                .option(ChannelOption.SO_BACKLOG, 128)
//                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) connectTimeout)
//                .childOption(ChannelOption.TCP_NODELAY, true)
//                .childOption(ChannelOption.SO_KEEPALIVE, true)
////                .childOption(ChannelOption.AUTO_READ, false)
//                .channel(NioServerSocketChannel.class)
//                .handler(new LoggingHandler(LogLevel.INFO))
//                .childHandler(new PacketServerHandler());
//        ChannelFuture f = b.bind(port).sync();
//        isStarted = true;
//        log.debug("Listened on port {}..", port);
//
//        if (waitClose) {
//            f.channel().closeFuture().sync();
//        }
//    }
//
//    protected T createClient(SessionChannelId sessionChannelId, ChannelHandlerContext ctx) {
//        T client = (T) Reflects.newInstance(clientSessionType, sessionChannelId, ctx);
//        client.setConnectedTime(DateTime.now());
//        return client;
//    }
//
//    protected void addClient(T client) {
//        clients.computeIfAbsent(client.getId().sessionId(), k -> Collections.synchronizedSet(new HashSet<>())).add(client);
//    }
//
//    protected T findClient(ChannelHandlerContext ctx) {
//        return NQuery.of(clients.values()).selectMany(p -> p).where(p -> p.channel == ctx).single();
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
//
//    public <TPack extends AppSessionPacket> void send(SessionChannelId sessionChannelId, TPack pack) {
//        checkNotClosed();
//        require(sessionChannelId, pack);
//
//        Set<T> set = clients.get(sessionChannelId.sessionId());
//        if (set == null) {
//            throw new InvalidOperationException(String.format("Client %s not found", sessionChannelId.getAppName()));
//        }
//        T client = NQuery.of(set).where(p -> eq(p.getId(), sessionChannelId)).firstOrDefault();
//        if (client == null) {
//            throw new InvalidOperationException(String.format("Client %s-%s not found", sessionChannelId.getAppName(), sessionChannelId.getChannelId()));
//        }
//        PackEventArgs<T> args = new PackEventArgs<>(client, pack);
//        raiseEvent(onSend, args);
//        if (args.isCancel()) {
//            return;
//        }
//        client.channel.writeAndFlush(pack);
//    }
//}
