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
import org.rx.core.Constants;
import org.rx.bean.IdGenerator;
import org.rx.core.*;
import org.rx.core.StringBuilder;
import org.rx.exception.TraceHandler;
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
import java.util.concurrent.Future;

import static org.rx.core.App.*;
import static org.rx.core.Extends.*;

@Slf4j
@RequiredArgsConstructor
public class RpcServer extends Disposable implements EventTarget<RpcServer> {
    class RpcClientImpl extends ChannelInboundHandlerAdapter implements RpcClient {
        final Delegate<RpcClient, NEventArgs<Serializable>> onReceive = Delegate.create();
        @Getter
        Channel channel;
        //cache meta
        @Getter
        InetSocketAddress remoteEndpoint;
        HandshakePacket handshakeMeta = NULL;

        @Override
        public boolean isConnected() {
            return channel != null && channel.isActive();
        }

        @Override
        public void send(Serializable pack) {
            checkNotClosed();

            RpcServerEventArgs<Serializable> args = new RpcServerEventArgs<>(this, pack);
            RpcServer.this.raiseEvent(onSend, args);
            if (args.isCancel() || !isConnected()) {
                log.warn("Send cancelled or client {} disconnected", remoteEndpoint);
                return;
            }

            channel.writeAndFlush(pack);
            log.debug("serverWrite {} {}", channel.remoteAddress(), pack.getClass());
        }

        @Override
        public Delegate<RpcClient, NEventArgs<Serializable>> onReceive() {
            return onReceive;
        }

        @Override
        public void close() {
            Sockets.closeOnFlushed(channel);
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
            RpcServerEventArgs<Serializable> args = new RpcServerEventArgs<>(this, null);
            RpcServer.this.raiseEvent(onConnected, args);
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
            if (tryAs(pack, HandshakePacket.class, p -> handshakeMeta = p)) {
                log.debug("Handshake: {}", toJsonString(handshakeMeta));
                return;
            }
            if (tryAs(pack, Long.class, p -> {
                ctx.writeAndFlush(p);
                RpcServer.this.raiseEventAsync(onPing, new RpcServerEventArgs<>(this, p));
                log.debug("serverHeartbeat pong {}", channel.remoteAddress());
            })) {
                return;
            }

            RpcServerEventArgs<Serializable> args = new RpcServerEventArgs<>(this, pack);
            raiseEvent(onReceive, args);
            RpcServer.this.raiseEventAsync(RpcServer.this.onReceive, args);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            log.info("serverInactive {}", ctx.channel().remoteAddress());
            clients.remove(getRemoteEndpoint());
            RpcServer.this.raiseEventAsync(onDisconnected, new RpcServerEventArgs<>(this, null));
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
            TraceHandler.INSTANCE.log("serverCaught {}", channel.remoteAddress(), cause);
            if (!channel.isActive()) {
                return;
            }

            RpcServerEventArgs<Throwable> args = new RpcServerEventArgs<>(this, cause);
            quietly(() -> RpcServer.this.raiseEvent(onError, args));
            if (args.isCancel()) {
                return;
            }
            Sockets.closeOnFlushed(channel);
        }

        @Override
        public void connect(InetSocketAddress remoteEp) {
            checkNotClosed();
            if (isConnected()) {
                return;
            }
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<Void> connectAsync(InetSocketAddress remoteEp) {
            checkNotClosed();
            if (isConnected()) {
                return CompletableFuture.completedFuture(null);
            }
            throw new UnsupportedOperationException();
        }
    }

    static final HandshakePacket NULL = new HandshakePacket();
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
    final Map<InetSocketAddress, RpcClientImpl> clients = new ConcurrentHashMap<>();
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

    public Map<InetSocketAddress, RpcClient> getClients() {
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
                    new RpcClientImpl());
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
        for (RpcClient client : Linq.from(clients.values()).orderByDescending(p -> p.handshakeMeta.getEventVersion())) {
            buf.append("\t%s", client.getRemoteEndpoint());
            if (i++ % 3 == 0) {
                buf.appendLine();
            }
        }
        return buf.toString();
    }

    public RpcClient getClient(InetSocketAddress remoteEndpoint) {
        return getClient(remoteEndpoint, true);
    }

    public RpcClient getClient(InetSocketAddress remoteEp, boolean throwOnEmpty) {
        checkNotClosed();

        RpcClientImpl handler = clients.get(remoteEp);
        if (handler == null && throwOnEmpty) {
            throw new ClientDisconnectedException(remoteEp);
        }
        return handler;
    }

    public void send(InetSocketAddress remoteEndpoint, Serializable pack) {
        getClient(remoteEndpoint).send(pack);
    }
}
