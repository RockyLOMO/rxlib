package org.rx.net.rpc;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.handler.codec.serialization.ObjectDecoder;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.bean.DateTime;
import org.rx.core.Constants;
import org.rx.bean.IdGenerator;
import org.rx.core.*;
import org.rx.core.StringBuilder;
import org.rx.exception.ExceptionHandler;
import org.rx.exception.InvalidException;
import org.rx.net.Sockets;
import org.rx.net.TransportUtil;
import org.rx.net.rpc.protocol.HandshakePacket;

import java.io.Serializable;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static org.rx.core.App.*;
import static org.rx.core.Extends.*;

@Slf4j
@RequiredArgsConstructor
public class RpcServer extends Disposable implements EventTarget<RpcServer> {
    class ClientHandler extends ChannelInboundHandlerAdapter implements RpcClientMeta {
        @Getter
        final HandshakePacket handshakePacket = new HandshakePacket();
        transient Channel channel;
        //cache meta
        @Getter
        InetSocketAddress remoteEndpoint;
        @Getter
        DateTime connectedTime;

        public boolean isConnected() {
            return channel != null && channel.isActive();
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

            clients.put(remoteEndpoint = (InetSocketAddress) channel.remoteAddress(), this);
            connectedTime = DateTime.now();
            RpcServerEventArgs<Serializable> args = new RpcServerEventArgs<>(this, null);
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
            if (tryAs(pack, HandshakePacket.class, p -> getHandshakePacket().setEventVersion(p.getEventVersion()))) {
                log.debug("Handshake: {}", toJsonString(getHandshakePacket()));
                return;
            }
            if (tryAs(pack, Long.class, p -> {
                ctx.writeAndFlush(p);
                raiseEventAsync(onPing, new RpcServerEventArgs<>(this, p));
                log.debug("serverHeartbeat pong {}", channel.remoteAddress());
            })) {
                return;
            }

            raiseEventAsync(onReceive, new RpcServerEventArgs<>(this, pack));
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            log.info("serverInactive {}", ctx.channel().remoteAddress());
            clients.remove(getRemoteEndpoint());
            raiseEventAsync(onDisconnected, new RpcServerEventArgs<>(this, null));
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
            ExceptionHandler.INSTANCE.log("serverCaught {}", channel.remoteAddress(), cause);
            if (!channel.isActive()) {
                return;
            }

            RpcServerEventArgs<Throwable> args = new RpcServerEventArgs<>(this, cause);
            quietly(() -> raiseEvent(onError, args));
            if (args.isCancel()) {
                return;
            }
            Sockets.closeOnFlushed(channel);
        }
    }

    public static final ThreadPool SCHEDULER = new ThreadPool(RpcServerConfig.REACTOR_NAME);
    public final Delegate<RpcServer, RpcServerEventArgs<Serializable>> onConnected = Delegate.create(),
            onDisconnected = Delegate.create(),
            onSend = Delegate.create(),
            onReceive = Delegate.create();
    public final Delegate<RpcServer, RpcServerEventArgs<Long>> onPing = Delegate.create();
    public final Delegate<RpcServer, RpcServerEventArgs<Throwable>> onError = Delegate.create();
    public final Delegate<RpcServer, EventArgs> onClosed = Delegate.create();

    @Getter
    final RpcServerConfig config;
    final Map<InetSocketAddress, ClientHandler> clients = new ConcurrentHashMap<>();
    ServerBootstrap bootstrap;
    volatile Channel serverChannel;

    @Override
    public @NonNull ThreadPool asyncScheduler() {
        return SCHEDULER;
    }

    public boolean isStarted() {
        return serverChannel != null;
    }

    @Override
    public <TArgs extends EventArgs> CompletableFuture<Void> raiseEventAsync(Delegate<RpcServer, TArgs> event, TArgs args) {
        ThreadPool scheduler = asyncScheduler();
        return scheduler.runAsync(() -> raiseEvent(event, args), String.format("ServerEvent%s", IdGenerator.DEFAULT.increment()), RunFlag.PRIORITY.flags());
    }

    public Map<InetSocketAddress, RpcClientMeta> getClients() {
        return Collections.unmodifiableMap(clients);
    }

    @Override
    protected void freeObjects() {
        if (isStarted()) {
            Sockets.closeOnFlushed(serverChannel);
        }
        Sockets.closeBootstrap(bootstrap);
        raiseEvent(onClosed, EventArgs.EMPTY);
    }

    @SneakyThrows
    public synchronized void start() {
        if (isStarted()) {
            throw new InvalidException("Server has started");
        }

        if (bootstrap != null) {
            Sockets.closeBootstrap(bootstrap);
        }
        bootstrap = Sockets.serverBootstrap(config, channel -> {
            //tcp keepalive OS层面，IdleStateHandler应用层面
            ChannelPipeline pipeline = channel.pipeline().addLast(new IdleStateHandler(RpcServerConfig.HEARTBEAT_TIMEOUT, 0, 0));
            TransportUtil.addFrontendHandler(channel, config);
            pipeline.addLast(RpcClientConfig.DEFAULT_ENCODER,
                    new ObjectDecoder(Constants.MAX_HEAP_BUF_SIZE, RpcClientConfig.DEFAULT_CLASS_RESOLVER),
                    new ClientHandler());
        }).option(ChannelOption.SO_REUSEADDR, true);
        bootstrap.bind(config.getListenPort()).addListeners(Sockets.logBind(config.getListenPort()), (ChannelFutureListener) f -> {
            if (!f.isSuccess()) {
                return;
            }
            serverChannel = f.channel();
        });
    }

    public String dumpClients() {
        StringBuilder buf = new StringBuilder();
        int i = 1;
        for (RpcClientMeta client : NQuery.of(clients.values()).orderByDescending(p -> p.getHandshakePacket().getEventVersion())) {
            buf.append("\t%s", client.getRemoteEndpoint());
            if (i++ % 3 == 0) {
                buf.appendLine();
            }
        }
        return buf.toString();
    }

    public RpcClientMeta getClient(InetSocketAddress remoteEndpoint) {
        return getHandler(remoteEndpoint, true);
    }

    protected ClientHandler getHandler(InetSocketAddress remoteEp, boolean throwOnDisconnected) {
        checkNotClosed();

        ClientHandler handler = clients.get(remoteEp);
        if (handler == null && throwOnDisconnected) {
            throw new ClientDisconnectedException(remoteEp);
        }
        return handler;
    }

    public void send(InetSocketAddress remoteEndpoint, Serializable pack) {
        send(getClient(remoteEndpoint), pack);
    }

    protected void send(@NonNull RpcClientMeta client, Serializable pack) {
        checkNotClosed();

        RpcServerEventArgs<Serializable> args = new RpcServerEventArgs<>(client, pack);
        raiseEvent(onSend, args);
        ClientHandler handler;
        if (args.isCancel()
                || (handler = getHandler(client.getRemoteEndpoint(), false)) == null || !handler.isConnected()) {
            log.warn("The client {} disconnected", client.getRemoteEndpoint());
            return;
        }

        handler.channel.writeAndFlush(pack);
        log.debug("serverWrite {} {}", handler.channel.remoteAddress(), pack.getClass());
    }

    public void close(@NonNull RpcClientMeta client) {
        ClientHandler handler = getHandler(client.getRemoteEndpoint(), false);
        if (handler == null) {
            return;
        }

        Sockets.closeOnFlushed(handler.channel);
    }
}
