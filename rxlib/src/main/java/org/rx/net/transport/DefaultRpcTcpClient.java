package org.rx.net.transport;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.handler.codec.serialization.ObjectDecoder;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.*;
import org.rx.exception.InvalidException;
import org.rx.net.Sockets;
import org.rx.net.transport.protocol.ErrorPacket;
import org.rx.net.transport.protocol.PingPacket;
import org.slf4j.helpers.MessageFormatter;

import java.io.Serializable;
import java.net.InetSocketAddress;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.rx.core.Extends.*;

@Slf4j
public class DefaultRpcTcpClient extends Disposable implements RpcTcpClient {
    @RequiredArgsConstructor
    static class ClientHandler extends ChannelInboundHandlerAdapter {
        final DefaultRpcTcpClient owner;

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            Channel channel = ctx.channel();
            log.debug("clientActive {}", channel.remoteAddress());

            owner.raiseEventAsync(owner.onConnected, EventArgs.EMPTY);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            Channel channel = ctx.channel();
            log.debug("clientRead {} {}", channel.remoteAddress(), msg.getClass());
            Serializable pack;
            if ((pack = as(msg, Serializable.class)) == null) {
                log.warn("clientRead discard {} {}", channel.remoteAddress(), msg.getClass());
                return;
            }
            if (tryAs(pack, ErrorPacket.class, p -> exceptionCaught(ctx, new InvalidException("Server error: {}", p.getErrorMessage())))) {
                return;
            }
            if (tryAs(pack, PingPacket.class, p -> {
                log.info("clientHeartbeat pong {} {}ms", channel.remoteAddress(), NtpClock.UTC.millis() - p.getTimestamp());
                owner.raiseEventAsync(owner.onPong, p);
            })) {
                return;
            }

            owner.raiseEventAsync(owner.onReceive, new NEventArgs<>(pack));
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            Channel channel = ctx.channel();
            log.info("clientInactive {}", channel.remoteAddress());

            owner.raiseEvent(owner.onDisconnected, EventArgs.EMPTY);
            owner.reconnectAsync();
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
            Channel channel = ctx.channel();
            if (evt instanceof IdleStateEvent) {
                IdleStateEvent e = (IdleStateEvent) evt;
                switch (e.state()) {
                    case READER_IDLE:
                        log.warn("clientHeartbeat loss {}", channel.remoteAddress());
                        ctx.close();
                        break;
                    case WRITER_IDLE:
                        log.debug("clientHeartbeat ping {}", channel.remoteAddress());
                        ctx.writeAndFlush(new PingPacket());
                        break;
                }
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            Channel channel = ctx.channel();
            log.error("clientCaught {}", channel.remoteAddress(), cause);
            if (!channel.isActive()) {
                return;
            }

            NEventArgs<Throwable> args = new NEventArgs<>(cause);
            quietly(() -> owner.raiseEvent(owner.onError, args));
            if (args.isCancel()) {
                return;
            }
            Sockets.closeOnFlushed(channel);
        }
    }

    static final TcpClientConfig NULL_CONF = new TcpClientConfig();
    public final Delegate<RpcTcpClient, EventArgs> onConnected = Delegate.create(),
            onDisconnected = Delegate.create();
    public final Delegate<RpcTcpClient, NEventArgs<InetSocketAddress>> onReconnecting = Delegate.create(),
            onReconnected = Delegate.create();
    public final Delegate<RpcTcpClient, NEventArgs<Serializable>> onSend = Delegate.create(),
            onReceive = Delegate.create();
    public final Delegate<RpcTcpClient, PingPacket> onPong = Delegate.create();
    public final Delegate<RpcTcpClient, NEventArgs<Throwable>> onError = Delegate.create();
    @Getter
    final TcpClientConfig config;
    //cache meta
    @Getter
    InetSocketAddress remoteEndpoint, localEndpoint;
    Bootstrap bootstrap;
    volatile CompletableFuture<Void> connectPromise;
    @Getter
    volatile Channel channel;
    volatile ChannelFuture connectingFuture;
    volatile InetSocketAddress connectingEp;

    @Override
    public @NonNull ThreadPool asyncScheduler() {
        return RpcTcpServer.SCHEDULER;
    }

    public boolean isConnected() {
        Channel c = channel;
        return c != null && c.isActive();
    }

    protected synchronized boolean isShouldReconnect() {
        return config.isEnableReconnect() && !isConnected();
    }

    public DefaultRpcTcpClient(@NonNull TcpClientConfig config) {
        this.config = config;
    }

    protected DefaultRpcTcpClient() {
        this.config = NULL_CONF;
    }

    @Override
    protected void dispose() {
        config.setEnableReconnect(false); //import
        CompletableFuture<Void> promise = connectPromise;
        if (promise != null && !promise.isDone()) {
            promise.completeExceptionally(new CancellationException("client closed"));
        }
        Sockets.closeOnFlushed(channel);
//        bootstrap.config().group().shutdownGracefully();
    }

    @Override
    public void connect(@NonNull InetSocketAddress remoteEp) throws TimeoutException {
        CompletableFuture<Void> promise = beginConnect(remoteEp);
        try {
            promise.get(config.getConnectTimeoutMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw InvalidException.sneaky(e);
        } catch (ExecutionException e) {
            TimeoutException ex = new TimeoutException(MessageFormatter.format("{} connect {} fail", this, remoteEp).getMessage());
            ex.initCause(e.getCause());
            throw ex;
        } catch (CancellationException e) {
            TimeoutException ex = new TimeoutException(MessageFormatter.format("{} connect {} cancelled", this, remoteEp).getMessage());
            ex.initCause(e);
            throw ex;
        }
    }

    private synchronized CompletableFuture<Void> beginConnect(@NonNull InetSocketAddress remoteEp) {
        if (isConnected()) {
            throw new InvalidException("{} has connected", this);
        }

        CompletableFuture<Void> promise = connectPromise;
        InetSocketAddress currentEp = config.getServerEndpoint();
        if (promise != null && !promise.isDone()) {
            if (currentEp != null && !currentEp.equals(remoteEp)) {
                throw new InvalidException("{} is connecting {}", this, currentEp);
            }
            return promise;
        }

        config.setReactorName(Sockets.ReactorNames.RPC);
        config.setServerEndpoint(remoteEp);
        bootstrap = Sockets.bootstrap(config, channel -> {
            ChannelPipeline pipeline = channel.pipeline().addLast(new IdleStateHandler(config.getHeartbeatTimeout(), config.getHeartbeatTimeout() / 2, 0));
            Sockets.addTcpClientHandler(channel, config, config.getServerEndpoint());
            pipeline.addLast(TcpClientConfig.DEFAULT_ENCODER,
                    new ObjectDecoder(Constants.MAX_HEAP_BUF_SIZE, TcpClientConfig.DEFAULT_CLASS_RESOLVER),
                    new ClientHandler(this));
        });
        connectPromise = promise = new CompletableFuture<Void>() {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                ChannelFuture f = connectingFuture;
                if (f != null) {
                    f.cancel(mayInterruptIfRunning);
                }
                return super.cancel(mayInterruptIfRunning);
            }
        };
        doConnect(false);
        return promise;
    }

    @Override
    public Future<Void> connectAsync(@NonNull InetSocketAddress remoteEp) {
        return beginConnect(remoteEp);
    }

    private void completeConnectSuccess() {
        CompletableFuture<Void> promise = connectPromise;
        if (promise != null && !promise.isDone()) {
            promise.complete(null);
        }
    }

    private void completeConnectFailure(Throwable cause) {
        CompletableFuture<Void> promise = connectPromise;
        if (promise != null && !promise.isDone()) {
            promise.completeExceptionally(cause);
        }
    }

    synchronized void doConnect(boolean reconnect) {
        InetSocketAddress ep;
        if (reconnect) {
            if (!isShouldReconnect()) {
                return;
            }

            NEventArgs<InetSocketAddress> args = new NEventArgs<>(ifNull(connectingEp, config.getServerEndpoint()));
            raiseEvent(onReconnecting, args);
            ep = connectingEp = args.getValue();
        } else {
            ep = config.getServerEndpoint();
        }

        connectingFuture = bootstrap.connect(ep).addListener((ChannelFutureListener) f -> {
            channel = f.channel();
            connectingFuture = null;
            if (!f.isSuccess()) {
                Throwable cause = f.cause();
                if (isShouldReconnect()) {
                    Tasks.timer().setTimeout(() -> {
                        doConnect(true);
                        circuitContinue(isShouldReconnect());
                    }, d -> {
                        long delay = d >= 5000 ? 5000 : Math.max(d * 2, 100);
                        log.warn("{} reconnect {} failed will re-attempt in {}ms", this, ep, delay);
                        return delay;
                    }, this, Constants.TIMER_SINGLE_FLAG);
                } else {
                    log.warn("{} {} {} fail", this, reconnect ? "reconnect" : "connect", ep);
                    completeConnectFailure(cause == null ? new InvalidException("{} {} {} fail", this, reconnect ? "reconnect" : "connect", ep) : cause);
                }
                return;
            }
            connectingEp = null;
            config.setServerEndpoint(ep);
            remoteEndpoint = (InetSocketAddress) channel.remoteAddress();
            localEndpoint = (InetSocketAddress) channel.localAddress();
            completeConnectSuccess();
            if (reconnect) {
                log.info("{} reconnect {} ok", this, ep);
                raiseEvent(onReconnected, new NEventArgs<>(ep));
            }
        });
    }

    void reconnectAsync() {
        Tasks.setTimeout(() -> doConnect(true), 1000, bootstrap, Constants.TIMER_REPLACE_FLAG);
    }

    @Override
    public void send(@NonNull Serializable pack) {
        // Fast path: channel is active, no locking needed — Netty's writeAndFlush is thread-safe
        if (!isConnected()) {
            // Slow path: reconnect in progress; synchronize only for the reconnect check/wait
            synchronized (this) {
                if (!isConnected()) {
                    if (isShouldReconnect()) {
                        if (!FluentWait.polling(config.getWaitConnectMillis()).awaitTrue(w -> isConnected())) {
                            reconnectAsync();
                            throw new ClientDisconnectedException(this);
                        }
                    }
                    if (!isConnected()) {
                        throw new ClientDisconnectedException(this);
                    }
                }
            }
        }

        NEventArgs<Serializable> args = new NEventArgs<>(pack);
        raiseEvent(onSend, args);
        if (args.isCancel()) {
            return;
        }

        channel.writeAndFlush(pack);
        log.debug("clientWrite {} {}", config.getServerEndpoint(), pack);
    }

    @Override
    public Delegate<RpcTcpClient, NEventArgs<Serializable>> onReceive() {
        return onReceive;
    }
}
