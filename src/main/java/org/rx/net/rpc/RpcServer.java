package org.rx.net.rpc;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.handler.codec.compression.ZlibCodecFactory;
import io.netty.handler.codec.compression.ZlibWrapper;
import io.netty.handler.codec.serialization.ClassResolvers;
import io.netty.handler.codec.serialization.ObjectDecoder;
import io.netty.handler.codec.serialization.ObjectEncoder;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.bean.RxConfig;
import org.rx.core.*;
import org.rx.core.StringBuilder;
import org.rx.core.exception.InvalidException;
import org.rx.net.ChannelClientHandler;
import org.rx.net.Sockets;
import org.rx.net.rpc.packet.HandshakePacket;
import org.rx.net.rpc.packet.PingMessage;

import java.io.Serializable;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

import static org.rx.core.App.*;

@Slf4j
@RequiredArgsConstructor
public class RpcServer extends Disposable implements EventTarget<RpcServer> {
    class ClientHandler extends ChannelClientHandler {
        private RpcServerClient client;

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            super.channelActive(ctx);
            Channel channel = ctx.channel();
            log.debug("serverActive {}", channel.remoteAddress());
            if (clients.size() > config.getCapacity()) {
                log.warn("Force close client, Not enough capacity {}/{}.", clients.size(), config.getCapacity());
                close();
                return;
            }

            client = new RpcServerClient(channel.id(), (InetSocketAddress) channel.remoteAddress());
            clients.put(channel.id(), this);
            RpcServerEventArgs<Serializable> args = new RpcServerEventArgs<>(client, null);
            raiseEvent(onConnected, args);
            if (args.isCancel()) {
                log.warn("Force close client");
                close();
            }
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            Channel channel = ctx.channel();
            log.debug("serverRead {} {}", channel.remoteAddress(), msg.getClass());

            Serializable pack;
            if ((pack = as(msg, Serializable.class)) == null) {
                log.warn("serverRead discard");
                close();
                return;
            }
            if (!RpcServer.this.isConnected(client) || tryAs(pack, HandshakePacket.class, p -> client.setHandshakePacket(p))) {
                log.debug("Handshake: {}", toJsonString(client.getHandshakePacket()));
                return;
            }
            if (tryAs(pack, PingMessage.class, p -> {
                p.setReplyTimestamp(System.currentTimeMillis());
                writeAndFlush(p);
                log.debug("serverHeartbeat pong {}", channel.remoteAddress());
            })) {
                return;
            }

            raiseEventAsync(onReceive, new RpcServerEventArgs<>(client, pack));
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            log.debug("serverInactive {}", ctx.channel().remoteAddress());
            clients.remove(client.getId());
            raiseEventAsync(onDisconnected, new RpcServerEventArgs<>(client, null));
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
            Channel channel = ctx.channel();
            if (evt instanceof IdleStateEvent) {
                IdleStateEvent e = (IdleStateEvent) evt;
                if (e.state() == IdleState.READER_IDLE) {
                    log.warn("serverHeartbeat loss {}", channel.remoteAddress());
                    ctx.close();
                }
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            Channel channel = ctx.channel();
            App.log("serverCaught {}", channel.remoteAddress(), cause);
            if (!channel.isActive()) {
                return;
            }

            RpcServerEventArgs<Throwable> args = new RpcServerEventArgs<>(client, cause);
            quietly(() -> raiseEvent(onError, args));
            if (args.isCancel()) {
                return;
            }
            close();
        }
    }

    public static final TaskScheduler SCHEDULER = new TaskScheduler("Rpc");
    public volatile BiConsumer<RpcServer, RpcServerEventArgs<Serializable>> onConnected, onDisconnected, onSend, onReceive;
    public volatile BiConsumer<RpcServer, RpcServerEventArgs<Throwable>> onError;
    public volatile BiConsumer<RpcServer, EventArgs> onClosed;

    @Getter
    private final RpcServerConfig config;
    private ServerBootstrap bootstrap;
    private SslContext sslCtx;
    private volatile Channel serverChannel;
    private final Map<ChannelId, ClientHandler> clients = new ConcurrentHashMap<>();
    @Getter
    private volatile boolean isStarted;

    @Override
    public @NonNull TaskScheduler asyncScheduler() {
        return SCHEDULER;
    }

    @Override
    public <TArgs extends EventArgs> CompletableFuture<Void> raiseEventAsync(BiConsumer<RpcServer, TArgs> event, TArgs args) {
        TaskScheduler scheduler = asyncScheduler();
        return scheduler.run(() -> raiseEvent(event, args), String.format("ServerEvent%s", scheduler.getGenerator().next()), RunFlag.PRIORITY);
    }

    public List<RpcServerClient> getClients() {
        return NQuery.of(clients.values()).select(p -> p.client).toList();
    }

    public RpcServerClient getClient(ChannelId id) {
        checkNotClosed();

        return getHandler(id).client;
    }

    protected ClientHandler getHandler(ChannelId id) {
        ClientHandler handler = clients.get(id);
        if (handler == null) {
            throw new InvalidException("Client not found");
        }
        return handler;
    }

    @Override
    protected synchronized void freeObjects() {
        isStarted = false;
        Sockets.closeOnFlushed(serverChannel);
        Sockets.closeBootstrap(bootstrap);
        raiseEvent(onClosed, EventArgs.EMPTY);
    }

    @SneakyThrows
    public synchronized void start() {
        if (isStarted) {
            throw new InvalidException("Server has started");
        }

        if (config.isEnableSsl()) {
            SelfSignedCertificate ssc = new SelfSignedCertificate();
            sslCtx = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();
        }
        bootstrap = Sockets.serverBootstrap(config.getMemoryMode(), channel -> {
            //tcp keepalive OS层面，IdleStateHandler应用层面
            ChannelPipeline pipeline = channel.pipeline().addLast(new IdleStateHandler(RpcServerConfig.HEARTBEAT_TIMEOUT, 0, 0));
            if (sslCtx != null) {
                pipeline.addLast(sslCtx.newHandler(channel.alloc()));
            }
            if (config.isEnableCompress()) {
                pipeline.addLast(ZlibCodecFactory.newZlibEncoder(ZlibWrapper.GZIP),
                        ZlibCodecFactory.newZlibDecoder(ZlibWrapper.GZIP));
            }
            pipeline.addLast(new ObjectEncoder(),
                    new ObjectDecoder(RxConfig.MAX_HEAP_BUF_SIZE, ClassResolvers.weakCachingConcurrentResolver(RpcServer.class.getClassLoader())),
                    new ClientHandler());
        }).option(ChannelOption.SO_REUSEADDR, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, config.getConnectTimeoutMillis());
        InetSocketAddress endpoint = Sockets.getAnyEndpoint(config.getListenPort());
        bootstrap.bind(endpoint).addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess()) {
                log.error("Listened on port {} fail..", endpoint, f.cause());
                isStarted = false;
                return;
            }
            serverChannel = f.channel();
            log.debug("Listened on port {}..", endpoint);
        });
        isStarted = true;
    }

    public String dump() {
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (RpcServerClient client : NQuery.of(clients.values()).select(p -> p.client).orderByDescending(p -> p.getHandshakePacket().getEventVersion())) {
            sb.append("\t%s:%s", client.getRemoteAddress(), client.getId());
            if (i++ % 3 == 0) {
                sb.appendLine();
            }
        }
        return sb.toString();
    }

    protected boolean isConnected(RpcServerClient client) {
        return isStarted && getHandler(client.getId()).isConnected();
    }

    public void send(ChannelId id, Serializable pack) {
        send(getClient(id), pack);
    }

    protected void send(@NonNull RpcServerClient client, Serializable pack) {
        checkNotClosed();

        RpcServerEventArgs<Serializable> args = new RpcServerEventArgs<>(client, pack);
        raiseEvent(onSend, args);
        if (args.isCancel() || !isConnected(client)) {
            log.warn("Client disconnected");
            return;
        }

        ClientHandler handler = getHandler(client.getId());
        handler.writeAndFlush(pack).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
        log.debug("serverWrite {} {}", handler.channel().remoteAddress(), pack.getClass());
    }

    public void closeClient(RpcServerClient client) {
        getHandler(client.getId()).close();
    }
}
