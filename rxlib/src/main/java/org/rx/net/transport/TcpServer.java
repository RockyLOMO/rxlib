package org.rx.net.transport;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Delegate;
import org.rx.core.Disposable;
import org.rx.core.EventArgs;
import org.rx.core.EventPublisher;
import org.rx.core.Linq;
import org.rx.core.NEventArgs;
import org.rx.core.NtpClock;
import org.rx.core.RunFlag;
import org.rx.core.ThreadPool;
import org.rx.exception.InvalidException;
import org.rx.net.Sockets;
import org.rx.net.transport.protocol.PingPacket;
import org.rx.util.IdGenerator;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.rx.core.Extends.quietly;
import static org.rx.core.Extends.tryAs;

@Slf4j
@RequiredArgsConstructor
public class TcpServer extends Disposable implements EventPublisher<TcpServer> {
    @RequiredArgsConstructor
    static class ClientImpl extends ChannelInboundHandlerAdapter implements TcpClient {
        final TcpServer owner;
        final Delegate<TcpClient, NEventArgs<Object>> onReceive = Delegate.create();
        @Getter
        Channel channel;
        @Getter
        InetSocketAddress remoteEndpoint;
        @Getter
        volatile long lastHeartbeatMillis;
        @Getter
        volatile long heartbeatRttMillis = -1L;
        boolean admitted;

        @Override
        public boolean isConnected() {
            return channel != null && channel.isActive();
        }

        @Override
        public void send(Object pack) {
            if (!isConnected()) {
                log.warn("Send cancelled or client {} disconnected", remoteEndpoint);
                return;
            }
            if (owner.isClosed()) {
                writeAndFlush(pack);
                log.debug("serverWrite (server closing) {} {}", channel.remoteAddress(), pack);
                return;
            }

            TcpServerEventArgs<Object> args = new TcpServerEventArgs<>(this, pack);
            owner.publishEvent(owner.onSend, args);
            if (args.isCancel() || !isConnected()) {
                log.warn("Send cancelled or client {} disconnected", remoteEndpoint);
                return;
            }

            writeAndFlush(pack);
            log.debug("serverWrite {} {}", channel.remoteAddress(), pack);
        }

        private void writeAndFlush(Object pack) {
            Channel ch = channel;
            if (ch.eventLoop().inEventLoop()) {
                ch.writeAndFlush(pack);
                return;
            }
            ch.eventLoop().execute(() -> {
                if (ch.isActive()) {
                    ch.writeAndFlush(pack);
                }
            });
        }

        @Override
        public Delegate<TcpClient, NEventArgs<Object>> onReceive() {
            return onReceive;
        }

        @Override
        public void close() {
            Sockets.closeOnFlushed(channel);
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            channel = ctx.channel();
            log.debug("serverActive {}", channel.remoteAddress());
            TcpServerConfig config = owner.config;
            Map<InetSocketAddress, ClientImpl> clients = owner.clients;
            int clientCount = owner.clientCount.incrementAndGet();
            if (clientCount > config.getCapacity()) {
                owner.clientCount.decrementAndGet();
                log.warn("Force close client, Not enough capacity {}/{}.", clientCount, config.getCapacity());
                Sockets.closeOnFlushed(channel);
                return;
            }

            admitted = true;
            clients.put(remoteEndpoint = (InetSocketAddress) channel.remoteAddress(), this);
            TcpServerEventArgs<Object> args = new TcpServerEventArgs<>(this, null);
            owner.publishEvent(owner.onConnected, args);
            if (args.isCancel()) {
                log.warn("Force close client");
                Sockets.closeOnFlushed(channel);
            }
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            Channel channel = ctx.channel();
            log.debug("serverRead {} {}", channel.remoteAddress(), msg);

            if (tryAs(msg, PingPacket.class, p -> {
                updateHeartbeat(p);
                ctx.writeAndFlush(p);
                log.debug("serverHeartbeat pong {}", channel.remoteAddress());
            })) {
                return;
            }

            TcpServerEventArgs<Object> args = new TcpServerEventArgs<>(this, msg);
            publishEvent(onReceive, args);
            owner.publishEventAsync(owner.onReceive, args);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            log.debug("serverInactive {}", ctx.channel().remoteAddress());
            if (admitted) {
                admitted = false;
                owner.clientCount.decrementAndGet();
            }
            InetSocketAddress endpoint = getRemoteEndpoint();
            if (endpoint != null) {
                owner.clients.remove(endpoint, this);
            }
            owner.publishEventAsync(owner.onDisconnected, new TcpServerEventArgs<>(this, null));
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
            log.error("serverCaught {}", channel.remoteAddress(), cause);
            if (!channel.isActive()) {
                return;
            }

            TcpServerEventArgs<Throwable> args = new TcpServerEventArgs<>(this, cause);
            quietly(() -> owner.publishEvent(owner.onError, args));
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

        void updateHeartbeat(PingPacket packet) {
            long now = NtpClock.UTC.millis();
            lastHeartbeatMillis = now;
            long rtt = (now - packet.getTimestamp()) << 1;
            heartbeatRttMillis = rtt < 0 ? 0 : rtt;
        }
    }

    static final ThreadPool SCHEDULER = Sockets.newRpcScheduler();
    public final Delegate<TcpServer, TcpServerEventArgs<Object>> onConnected = Delegate.create(),
            onDisconnected = Delegate.create(),
            onSend = Delegate.create(),
            onReceive = Delegate.create();
    public final Delegate<TcpServer, TcpServerEventArgs<Throwable>> onError = Delegate.create();
    public final Delegate<TcpServer, EventArgs> onClosed = Delegate.create();

    @Getter
    final TcpServerConfig config;
    final Map<InetSocketAddress, ClientImpl> clients = new ConcurrentHashMap<>();
    final AtomicInteger clientCount = new AtomicInteger();
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
    public <TArgs> CompletableFuture<Void> publishEventAsync(Delegate<TcpServer, TArgs> event, TArgs args) {
        ThreadPool scheduler = asyncScheduler();
        return scheduler.runAsync(() -> {
            publishEvent(event, args);
            return null;
        }, String.format("ServerEvent%s", IdGenerator.DEFAULT.increment()), RunFlag.PRIORITY.flags());
    }

    @SuppressWarnings("unchecked")
    public Map<InetSocketAddress, TcpClient> getClients() {
        return (Map<InetSocketAddress, TcpClient>) (Map) Collections.unmodifiableMap(clients);
    }

    @Override
    protected void dispose() {
        if (serverChannel != null && serverChannel.isOpen()) {
            serverChannel.close();
        }
        Sockets.closeBootstrap(bootstrap);
        publishEvent(onClosed, EventArgs.EMPTY);
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
            ChannelPipeline pipeline = channel.pipeline().addLast(new IdleStateHandler(config.getHeartbeatTimeout(), 0, 0));
            Sockets.addTcpServerHandler(channel, config);
            TcpChannelCodec codec = config.getCodec();
            if (codec != null) {
                codec.install(pipeline);
            }
            pipeline.addLast(new ClientImpl(this));
        }).option(ChannelOption.SO_REUSEADDR, true);
        ChannelFuture bindFuture = bootstrap.bind(config.getListenPort()).syncUninterruptibly();
        if (!bindFuture.isSuccess()) {
            throw new InvalidException("Server bind {} failed: {}", config.getListenPort(), bindFuture.cause());
        }
        serverChannel = bindFuture.channel();
    }

    public String dumpClients() {
        org.rx.core.StringBuilder buf = new org.rx.core.StringBuilder();
        int i = 1;
        for (ClientImpl client : Linq.from(clients.values()).orderBy(p -> p.remoteEndpoint)) {
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

    public void send(InetSocketAddress remoteEndpoint, Object pack) {
        getClient(remoteEndpoint).send(pack);
    }
}
