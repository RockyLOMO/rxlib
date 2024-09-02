package org.rx.net.transport;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.handler.codec.serialization.ObjectDecoder;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.*;
import org.rx.exception.InvalidException;
import org.rx.exception.TraceHandler;
import org.rx.net.Sockets;
import org.rx.net.transport.protocol.ErrorPacket;
import org.rx.net.transport.protocol.PingPacket;
import org.slf4j.helpers.MessageFormatter;

import java.io.Serializable;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.rx.core.Extends.*;

@Slf4j
public class StatefulTcpClient extends Disposable implements TcpClient {
    class ClientHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            Channel channel = ctx.channel();
            log.debug("clientActive {}", channel.remoteAddress());

            raiseEventAsync(onConnected, EventArgs.EMPTY);
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
                raiseEventAsync(onPong, p);
            })) {
                return;
            }

            raiseEventAsync(onReceive, new NEventArgs<>(pack));
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            Channel channel = ctx.channel();
            log.info("clientInactive {}", channel.remoteAddress());

            raiseEvent(onDisconnected, EventArgs.EMPTY);
            reconnectAsync();
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
            TraceHandler.INSTANCE.log("clientCaught {}", channel.remoteAddress(), cause);
            if (!channel.isActive()) {
                return;
            }

            NEventArgs<Throwable> args = new NEventArgs<>(cause);
            quietly(() -> raiseEvent(onError, args));
            if (args.isCancel()) {
                return;
            }
            close();
        }
    }

    static final TcpClientConfig NULL_CONF = new TcpClientConfig();
    public final Delegate<TcpClient, EventArgs> onConnected = Delegate.create(),
            onDisconnected = Delegate.create();
    public final Delegate<TcpClient, NEventArgs<InetSocketAddress>> onReconnecting = Delegate.create(),
            onReconnected = Delegate.create();
    public final Delegate<TcpClient, NEventArgs<Serializable>> onSend = Delegate.create(),
            onReceive = Delegate.create();
    public final Delegate<TcpClient, PingPacket> onPong = Delegate.create();
    public final Delegate<TcpClient, NEventArgs<Throwable>> onError = Delegate.create();
    @Getter
    final TcpClientConfig config;
    @Setter
    long sendWaitConnectMillis = 4000;
    //cache meta
    @Getter
    InetSocketAddress remoteEndpoint, localEndpoint;
    Bootstrap bootstrap;
    @Getter
    volatile Channel channel;
    volatile ChannelFuture connectingFuture;
    volatile InetSocketAddress connectingEp;

    @Override
    public @NonNull ThreadPool asyncScheduler() {
        return TcpServer.SCHEDULER;
    }

    public boolean isConnected() {
        return channel != null && channel.isActive();
    }

    protected boolean isShouldReconnect() {
        return config.isEnableReconnect() && !isConnected();
    }

    public StatefulTcpClient(@NonNull TcpClientConfig config) {
        this.config = config;
    }

    protected StatefulTcpClient() {
        this.config = NULL_CONF;
    }

    @Override
    protected void freeObjects() {
        config.setEnableReconnect(false); //import
        Sockets.closeOnFlushed(channel);
//        bootstrap.config().group().shutdownGracefully();
    }

    @Override
    public synchronized void connect(@NonNull InetSocketAddress remoteEp) throws TimeoutException {
        if (isConnected()) {
            throw new InvalidException("Client has connected");
        }

        config.setServerEndpoint(remoteEp);
        bootstrap = Sockets.bootstrap(Sockets.ReactorNames.RPC, config, channel -> {
            ChannelPipeline pipeline = channel.pipeline().addLast(new IdleStateHandler(config.getHeartbeatTimeout(), config.getHeartbeatTimeout() / 2, 0));
            Sockets.addBackendHandler(channel, config, config.getServerEndpoint());
            pipeline.addLast(TcpClientConfig.DEFAULT_ENCODER,
                    new ObjectDecoder(Constants.MAX_HEAP_BUF_SIZE, TcpClientConfig.DEFAULT_CLASS_RESOLVER),
                    new ClientHandler());
        });
        final Object syncRoot = config;
        doConnect(false, syncRoot);
        synchronized (syncRoot) {
            try {
                syncRoot.wait(config.getConnectTimeoutMillis());
            } catch (InterruptedException e) {
                throw InvalidException.sneaky(e);
            }
        }
        if (!isConnected()) {
            throw new TimeoutException(MessageFormatter.format("Client connect {} timeout", config.getServerEndpoint()).getMessage());
        }
    }

    @Override
    public synchronized Future<Void> connectAsync(@NonNull InetSocketAddress remoteEp) {
        if (isConnected()) {
            throw new InvalidException("Client has connected");
        }

        config.setServerEndpoint(remoteEp);
        bootstrap = Sockets.bootstrap(Sockets.ReactorNames.RPC, config, channel -> {
            ChannelPipeline pipeline = channel.pipeline().addLast(new IdleStateHandler(config.getHeartbeatTimeout(), config.getHeartbeatTimeout() / 2, 0));
            Sockets.addBackendHandler(channel, config, config.getServerEndpoint());
            pipeline.addLast(TcpClientConfig.DEFAULT_ENCODER,
                    new ObjectDecoder(Constants.MAX_HEAP_BUF_SIZE, TcpClientConfig.DEFAULT_CLASS_RESOLVER),
                    new ClientHandler());
        });
        doConnect(false, null);
        return new Future<Void>() {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                config.setEnableReconnect(false);
                return true;
            }

            @Override
            public boolean isCancelled() {
                return !config.isEnableReconnect();
            }

            @Override
            public boolean isDone() {
                return isConnected();
            }

            @Override
            public Void get() throws InterruptedException, ExecutionException {
                ChannelFuture f = connectingFuture;
                if (f == null) {
                    return null;
                }
                return f.get();
            }

            @Override
            public Void get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
                ChannelFuture f = connectingFuture;
                if (f == null) {
                    return null;
                }
                return f.get(timeout, unit);
            }
        };
    }

    synchronized void doConnect(boolean reconnect, Object syncRoot) {
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

        connectingFuture = bootstrap.connect(ep).addListeners(Sockets.logConnect(config.getServerEndpoint()), (ChannelFutureListener) f -> {
            channel = f.channel();
            if (!f.isSuccess()) {
                if (isShouldReconnect()) {
                    Tasks.timer().setTimeout(() -> {
                        doConnect(true, syncRoot);
                        circuitContinue(isShouldReconnect());
                    }, d -> {
                        long delay = d >= 5000 ? 5000 : Math.max(d * 2, 100);
                        log.warn("{} reconnect {} failed will re-attempt in {}ms", this, ep, delay);
                        return delay;
                    }, this, Constants.TIMER_SINGLE_FLAG);
                } else {
                    log.warn("{} {} fail", reconnect ? "reconnect" : "connect", ep);
                }
                return;
            }
            connectingEp = null;
            connectingFuture = null;
            config.setServerEndpoint(ep);
            remoteEndpoint = (InetSocketAddress) channel.remoteAddress();
            localEndpoint = (InetSocketAddress) channel.localAddress();

            if (syncRoot != null) {
                synchronized (syncRoot) {
                    syncRoot.notifyAll();
                }
            }
            if (reconnect) {
                log.info("reconnect {} ok", ep);
                raiseEvent(onReconnected, new NEventArgs<>(ep));
            }
        });
    }

    void reconnectAsync() {
        Tasks.setTimeout(() -> doConnect(true, null), 1000, bootstrap, Constants.TIMER_REPLACE_FLAG);
    }

    ChannelId channelId() {
        return channel != null ? channel.id() : null;
    }

    @Override
    public synchronized void send(@NonNull Serializable pack) {
        if (!isConnected()) {
            if (isShouldReconnect()) {
                if (!FluentWait.polling(sendWaitConnectMillis).awaitTrue(w -> isConnected())) {
                    reconnectAsync();
                    throw new ClientDisconnectedException(channelId());
                }
            }
            if (!isConnected()) {
                throw new ClientDisconnectedException(channelId());
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
    public Delegate<TcpClient, NEventArgs<Serializable>> onReceive() {
        return onReceive;
    }
}
