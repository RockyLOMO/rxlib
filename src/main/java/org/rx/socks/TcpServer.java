package org.rx.socks;

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

import static org.rx.common.Contract.as;
import static org.rx.common.Contract.require;
import static org.rx.util.AsyncTask.TaskFactory;

@Slf4j
public class TcpServer<T extends TcpServer.ClientSession> extends Disposable {
    @RequiredArgsConstructor
    public class ClientSession {
        @Getter
        private final SessionChannelId id;
        @Getter
        private final transient ChannelHandlerContext channel;
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
            if (!(msg instanceof SessionPack)) {
                ctx.writeAndFlush(SessionPack.error("Error pack"));
                TaskFactory.scheduleOnce(ctx::close, 4 * 1000);
                return;
            }
            if (SessionPack.class.equals(msg.getClass())) {
                SessionPack sessionPack = (SessionPack) msg;
                SessionChannelId sessionChannelId = new SessionChannelId(ctx.channel().id());
                sessionChannelId.sessionId(sessionPack);
                T client = createClient(sessionChannelId, ctx);
                addClient(client);
                EventArgs.raiseEvent(onConnected, _this(), new NEventArgs<>(client));
                return;
            }
            EventArgs.raiseEvent(onReceive, _this(), new PackEventArgs<>( findClient(ctx), (SocksPack) msg));
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            super.channelActive(ctx);
            log.info("channelActive {}", ctx.channel().remoteAddress());
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            super.channelInactive(ctx);
            log.info("channelInactive {}", ctx.channel().remoteAddress());

            ShardingClientEntity client = findClient(ctx);
            EventArgs.raiseEvent(onDisconnected, _this(), new NEventArgs<>(client));
            removeClient(ctx);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            super.exceptionCaught(ctx, cause);
            log.error("exceptionCaught {}", ctx.channel().remoteAddress(), cause);
            ctx.close();
        }
    }

    public volatile BiConsumer<TcpServer<T>, NEventArgs<T>> onConnected;
    public volatile BiConsumer<TcpServer<T>, PackEventArgs<T>> onSend, onReceive;
    private int port;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private SslContext sslCtx;
    @Getter
    private volatile boolean isStarted;
    @Getter
    private final Map<SessionId, Set<T>> clients;
    private Class clientSessionType;

    private TcpServer<T> _this() {
        return this;
    }

    protected Class getClientSessionType() {
        return clientSessionType == null ? TcpServer.ClientSession.class : clientSessionType;
    }

    protected void setClientSessionType(Class type) {
        require(type, TcpServer.ClientSession.class.isAssignableFrom(type));

        clientSessionType = type;
    }

    @SneakyThrows
    public TcpServer(int port, boolean ssl) {
        clients = new ConcurrentHashMap<>();
        this.port = port;
        if (ssl) {
            SelfSignedCertificate ssc = new SelfSignedCertificate();
            sslCtx = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();
        }
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
        T client = (T) App.newInstance(getClientSessionType(), sessionChannelId, ctx);
        client.setConnectedTime(DateTime.now());
        return client;
    }

    protected void addClient(T client) {
        clients.computeIfAbsent(client.getId().sessionId(), k -> Collections.synchronizedSet(new HashSet<>())).add(client);
    }

    private ShardingClientEntity findClient(ChannelHandlerContext ctx) {
        return NQuery.of(clients.values()).selectMany(p -> p).where(p -> p.getChannel() == ctx).single();
    }

    private void removeClient(ChannelHandlerContext ctx) {
        NQuery.of(clients.values()).firstOrDefault(p -> p.removeIf(x -> x.getChannel() == ctx));
    }

    public <T extends SocksPack> void send(ChannelIdEntity channelId, T pack) {
        checkNotClosed();
        require(channelId, pack);
        Set<ShardingClientEntity> set = clients.get(channelId.getSocksId());
        if (set == null) {
            return;
        }
        ShardingClientEntity c = NQuery.of(set).where(p -> eq(p.getId(), channelId)).firstOrDefault();
        if (c == null) {
            return;
        }
        c.getChannel().writeAndFlush(pack);
    }
}
