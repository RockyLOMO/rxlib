package org.rx.net.rpc;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.handler.codec.serialization.ClassResolvers;
import io.netty.handler.codec.serialization.ObjectDecoder;
import io.netty.handler.codec.serialization.ObjectEncoder;
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
import org.rx.net.DuplexHandler;
import org.rx.net.Sockets;
import org.rx.net.TransportUtil;
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
    class ClientHandler extends DuplexHandler {
        private RpcServerClient client;
        private Channel channel;

        public boolean isConnected() {
            return channel != null && channel.isActive();
        }

        public ChannelHandlerContext context() {
            if (!isConnected()) {
                throw new ClientDisconnectedException(channel.id());
            }
            return channel.pipeline().lastContext();
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
//            super.channelActive(ctx);
            channel = ctx.channel();
            log.debug("serverActive {}", channel.remoteAddress());
            if (clients.size() > config.getCapacity()) {
                log.warn("Force close client, Not enough capacity {}/{}.", clients.size(), config.getCapacity());
                Sockets.closeOnFlushed(channel);
                return;
            }

            client = new RpcServerClient(channel.id(), (InetSocketAddress) channel.remoteAddress());
            clients.put(channel.id(), this);
            RpcServerEventArgs<Serializable> args = new RpcServerEventArgs<>(client, null);
            raiseEvent(onConnected, args);
            if (args.isCancel()) {
                log.warn("Force close client");
                Sockets.closeOnFlushed(channel);
            }
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            Channel channel = ctx.channel();
            log.debug("serverRead {} {}", channel.remoteAddress(), msg.getClass());

            Serializable pack;
            if ((pack = as(msg, Serializable.class)) == null) {
                log.warn("serverRead discard");
                Sockets.closeOnFlushed(channel);
                return;
            }
            if (tryAs(pack, HandshakePacket.class, p -> client.setHandshakePacket(p))) {
                log.debug("Handshake: {}", toJsonString(client.getHandshakePacket()));
                return;
            }
            if (tryAs(pack, PingMessage.class, p -> {
                p.setReplyTimestamp(System.currentTimeMillis());
                writeAndFlush(ctx, p);
                raiseEventAsync(onPing, new RpcServerEventArgs<>(client, p));
                log.debug("serverHeartbeat pong {}", channel.remoteAddress());
            })) {
                return;
            }

            raiseEventAsync(onReceive, new RpcServerEventArgs<>(client, pack));
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            log.info("serverInactive {}", ctx.channel().remoteAddress());
            clients.remove(client.getId());
            raiseEventAsync(onDisconnected, new RpcServerEventArgs<>(client, null));
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
            if (evt instanceof IdleStateEvent) {
                IdleStateEvent e = (IdleStateEvent) evt;
                if (e.state() == IdleState.READER_IDLE) {
                    log.warn("serverHeartbeat loss {}", ctx.channel().remoteAddress());
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
            Sockets.closeOnFlushed(channel);
        }
    }

    public static final TaskScheduler SCHEDULER = new TaskScheduler("Rpc");
    public volatile BiConsumer<RpcServer, RpcServerEventArgs<Serializable>> onConnected, onDisconnected, onSend, onReceive;
    public volatile BiConsumer<RpcServer, RpcServerEventArgs<PingMessage>> onPing;
    public volatile BiConsumer<RpcServer, RpcServerEventArgs<Throwable>> onError;
    public volatile BiConsumer<RpcServer, EventArgs> onClosed;

    @Getter
    private final RpcServerConfig config;
    private ServerBootstrap bootstrap;
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

    @Override
    protected void freeObjects() {
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

        bootstrap = Sockets.serverBootstrap(config, channel -> {
            //tcp keepalive OS层面，IdleStateHandler应用层面
            ChannelPipeline pipeline = channel.pipeline().addLast(new IdleStateHandler(RpcServerConfig.HEARTBEAT_TIMEOUT, 0, 0));
            TransportUtil.addFrontendHandler(channel, config);
            pipeline.addLast(new ObjectEncoder(),
                    new ObjectDecoder(RxConfig.MAX_HEAP_BUF_SIZE, ClassResolvers.weakCachingConcurrentResolver(RpcServer.class.getClassLoader())),
                    new ClientHandler());
        }).option(ChannelOption.SO_REUSEADDR, true);
        bootstrap.bind(config.getListenPort()).addListeners(Sockets.logBind(config.getListenPort()), (ChannelFutureListener) f -> {
            if (!f.isSuccess()) {
                isStarted = false;
                return;
            }
            serverChannel = f.channel();
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
        ClientHandler handler;
        return isStarted && (handler = getHandler(client.getId(), false)) != null && handler.isConnected();
    }

    public RpcServerClient getClient(ChannelId id) {
        return getHandler(id, true).client;
    }

    protected ClientHandler getHandler(ChannelId id, boolean throwOnDisconnected) {
        checkNotClosed();

        ClientHandler handler = clients.get(id);
        if (handler == null && throwOnDisconnected) {
            throw new ClientDisconnectedException(id);
        }
        return handler;
    }

    public void send(ChannelId id, Serializable pack) {
        send(getClient(id), pack);
    }

    protected void send(@NonNull RpcServerClient client, Serializable pack) {
        checkNotClosed();

        RpcServerEventArgs<Serializable> args = new RpcServerEventArgs<>(client, pack);
        raiseEvent(onSend, args);
        ClientHandler handler;
        if (args.isCancel() || (handler = getHandler(client.getId(), false)) == null) {
            log.warn("The client {} disconnected", client.getId());
            return;
        }

        handler.writeAndFlush(handler.context(), pack).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
        log.debug("serverWrite {} {}", handler.channel.remoteAddress(), pack.getClass());
    }

    public void close(@NonNull RpcServerClient client) {
        ClientHandler handler = getHandler(client.getId(), false);
        if (handler == null) {
            return;
        }

        Sockets.closeOnFlushed(handler.channel);
    }
}
