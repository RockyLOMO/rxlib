package org.rx.net.transport;

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
import org.rx.core.*;
import org.rx.core.StringBuilder;
import org.rx.exception.InvalidException;
import org.rx.exception.TraceHandler;
import org.rx.net.Sockets;
import org.rx.net.transport.protocol.PingPacket;
import org.rx.util.IdGenerator;

import java.io.Serializable;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

import static org.rx.core.Extends.*;

@Slf4j
@RequiredArgsConstructor
public class TcpServer extends Disposable implements EventPublisher<TcpServer> {
    @RequiredArgsConstructor
    static class ClientImpl extends ChannelInboundHandlerAdapter implements TcpClient {
        final TcpServer owner;
        final Delegate<TcpClient, NEventArgs<Serializable>> onReceive = Delegate.create();
        @Getter
        Channel channel;
        //cache meta
        @Getter
        InetSocketAddress remoteEndpoint;

        @Override
        public boolean isConnected() {
            return channel != null && channel.isActive();
        }

        @Override
        public void send(Serializable pack) {
            owner.checkNotClosed();

            TcpServerEventArgs<Serializable> args = new TcpServerEventArgs<>(this, pack);
            owner.raiseEvent(owner.onSend, args);
            if (args.isCancel() || !isConnected()) {
                log.warn("Send cancelled or client {} disconnected", remoteEndpoint);
                return;
            }

            channel.writeAndFlush(pack);
            log.debug("serverWrite {} {}", channel.remoteAddress(), pack);
        }

        @Override
        public Delegate<TcpClient, NEventArgs<Serializable>> onReceive() {
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
            TcpServerConfig config = owner.config;
            Map<InetSocketAddress, ClientImpl> clients = owner.clients;
            if (clients.size() > config.getCapacity()) {
                log.warn("Force close client, Not enough capacity {}/{}.", clients.size(), config.getCapacity());
                Sockets.closeOnFlushed(channel);
                return;
            }

            clients.put(remoteEndpoint = (InetSocketAddress) channel.remoteAddress(), this);
            TcpServerEventArgs<Serializable> args = new TcpServerEventArgs<>(this, null);
            owner.raiseEvent(owner.onConnected, args);
            if (args.isCancel()) {
                log.warn("Force close client");
                Sockets.closeOnFlushed(channel);
            }
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            Channel channel = ctx.channel();
            log.debug("serverRead {} {}", channel.remoteAddress(), msg);

            Serializable pack;
            if ((pack = as(msg, Serializable.class)) == null) {
                log.warn("serverRead discard");
                Sockets.closeOnFlushed(channel);
                return;
            }
            if (tryAs(pack, PingPacket.class, p -> {
                ctx.writeAndFlush(p);
                owner.raiseEventAsync(owner.onPing, new TcpServerEventArgs<>(this, p));
                log.debug("serverHeartbeat pong {}", channel.remoteAddress());
            })) {
                return;
            }

            TcpServerEventArgs<Serializable> args = new TcpServerEventArgs<>(this, pack);
            raiseEvent(onReceive, args);
            owner.raiseEventAsync(owner.onReceive, args);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            log.debug("serverInactive {}", ctx.channel().remoteAddress());
            owner.clients.remove(getRemoteEndpoint());
            owner.raiseEventAsync(owner.onDisconnected, new TcpServerEventArgs<>(this, null));
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

            TcpServerEventArgs<Throwable> args = new TcpServerEventArgs<>(this, cause);
            quietly(() -> owner.raiseEvent(owner.onError, args));
            if (args.isCancel()) {
                return;
            }
            Sockets.closeOnFlushed(channel);
        }

        @Override
        public void connect(InetSocketAddress remoteEp) {
            owner.checkNotClosed();
            if (isConnected()) {
                return;
            }
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<Void> connectAsync(InetSocketAddress remoteEp) {
            owner.checkNotClosed();
            if (isConnected()) {
                return CompletableFuture.completedFuture(null);
            }
            throw new UnsupportedOperationException();
        }
    }

    static final ThreadPool SCHEDULER = new ThreadPool(Sockets.ReactorNames.RPC);
    public final Delegate<TcpServer, TcpServerEventArgs<Serializable>> onConnected = Delegate.create(),
            onDisconnected = Delegate.create(),
            onSend = Delegate.create(),
            onReceive = Delegate.create();
    public final Delegate<TcpServer, TcpServerEventArgs<PingPacket>> onPing = Delegate.create();
    public final Delegate<TcpServer, TcpServerEventArgs<Throwable>> onError = Delegate.create();
    public final Delegate<TcpServer, EventArgs> onClosed = Delegate.create();

    @Getter
    final TcpServerConfig config;
    final Map<InetSocketAddress, ClientImpl> clients = new ConcurrentHashMap<>();
    ServerBootstrap bootstrap;
    Channel serverChannel;

    @Override
    public @NonNull ThreadPool asyncScheduler() {
        return SCHEDULER;
    }

    public boolean isStarted() {
        return serverChannel != null;
    }

    @Override
    public <TArgs> CompletableFuture<Void> raiseEventAsync(Delegate<TcpServer, TArgs> event, TArgs args) {
        ThreadPool scheduler = asyncScheduler();
        return scheduler.runAsync(() -> raiseEvent(event, args), String.format("ServerEvent%s", IdGenerator.DEFAULT.increment()), RunFlag.PRIORITY.flags());
    }

    public Map<InetSocketAddress, TcpClient> getClients() {
        return Collections.unmodifiableMap(clients);
    }

    @Override
    protected void dispose() {
        Sockets.closeOnFlushed(serverChannel);
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
            //tcp keepalive OS level，IdleStateHandler APP level
            ChannelPipeline pipeline = channel.pipeline().addLast(new IdleStateHandler(config.getHeartbeatTimeout(), 0, 0));
            Sockets.addServerHandler(channel, config);
            pipeline.addLast(TcpClientConfig.DEFAULT_ENCODER,
                    new ObjectDecoder(Constants.MAX_HEAP_BUF_SIZE, TcpClientConfig.DEFAULT_CLASS_RESOLVER),
                    new ClientImpl(this));
        }).option(ChannelOption.SO_REUSEADDR, true);
        serverChannel = bootstrap.bind(config.getListenPort()).addListener(Sockets.logBind(config.getListenPort())).channel();
    }

    public String dumpClients() {
        StringBuilder buf = new StringBuilder();
        int i = 1;
        for (TcpClient client : Linq.from(clients.values()).orderBy(p -> p.remoteEndpoint)) {
            buf.appendFormat("\t%s", client.getRemoteEndpoint());
            if (i++ % 3 == 0) {
                buf.appendLine();
            }
        }
        return buf.toString();
    }

    public TcpClient getClient(InetSocketAddress remoteEndpoint) {
        return getClient(remoteEndpoint, true);
    }

    public TcpClient getClient(InetSocketAddress remoteEp, boolean throwOnEmpty) {
        checkNotClosed();

        ClientImpl handler = clients.get(remoteEp);
        if (handler == null && throwOnEmpty) {
            throw new ClientDisconnectedException(remoteEp);
        }
        return handler;
    }

    public void send(InetSocketAddress remoteEndpoint, Serializable pack) {
        getClient(remoteEndpoint).send(pack);
    }
}
