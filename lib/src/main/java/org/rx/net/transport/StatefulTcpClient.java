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
import org.rx.exception.TraceHandler;
import org.rx.exception.InvalidException;
import org.rx.net.Sockets;
import org.rx.net.TransportUtil;
import org.rx.net.transport.protocol.ErrorPacket;
import org.rx.net.transport.protocol.PingPacket;

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

            //握手需要异步
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
                raiseEventAsync(onPong, new NEventArgs<>(p));
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
            Tasks.setTimeout(() -> doConnect(true, null), 1000);
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
    public final Delegate<TcpClient, NEventArgs<PingPacket>> onPong = Delegate.create();
    public final Delegate<TcpClient, NEventArgs<Throwable>> onError = Delegate.create();
    @Getter
    final TcpClientConfig config;
    @Setter
    long sendWaitConnectMillis = 5000;
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
    public synchronized void connect(@NonNull InetSocketAddress remoteEp) {
        if (isConnected()) {
            throw new InvalidException("Client has connected");
        }

        config.setServerEndpoint(remoteEp);
        bootstrap = Sockets.bootstrap(TcpServerConfig.REACTOR_NAME, config, channel -> {
            ChannelPipeline pipeline = channel.pipeline().addLast(new IdleStateHandler(config.getHeartbeatTimeout(), config.getHeartbeatTimeout() / 2, 0));
            TransportUtil.addBackendHandler(channel, config, config.getServerEndpoint());
            pipeline.addLast(TcpClientConfig.DEFAULT_ENCODER,
                    new ObjectDecoder(Constants.MAX_HEAP_BUF_SIZE, TcpClientConfig.DEFAULT_CLASS_RESOLVER),
                    new ClientHandler());
        });
        ResetEventWait syncRoot = new ResetEventWait();
        doConnect(false, syncRoot);
        try {
            syncRoot.waitOne(config.getConnectTimeoutMillis());
            syncRoot.reset();
        } catch (TimeoutException e) {
            throw new InvalidException("Client connect fail", e);
        }
        if (!config.isEnableReconnect() && !isConnected()) {
            throw new InvalidException("Client connect {} fail", config.getServerEndpoint());
        }
    }

    @Override
    public synchronized Future<Void> connectAsync(@NonNull InetSocketAddress remoteEp) {
        if (isConnected()) {
            throw new InvalidException("Client has connected");
        }

        config.setServerEndpoint(remoteEp);
        bootstrap = Sockets.bootstrap(TcpServerConfig.REACTOR_NAME, config, channel -> {
            ChannelPipeline pipeline = channel.pipeline().addLast(new IdleStateHandler(config.getHeartbeatTimeout(), config.getHeartbeatTimeout() / 2, 0));
            TransportUtil.addBackendHandler(channel, config, config.getServerEndpoint());
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
                return connectingFuture.get();
            }

            @Override
            public Void get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
                return connectingFuture.get(timeout, unit);
            }
        };
    }

    synchronized void doConnect(boolean reconnect, ResetEventWait syncRoot) {
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
                    }, this, TimeoutFlag.SINGLE.flags());
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
                syncRoot.set();
            }
            if (reconnect) {
                log.info("reconnect {} ok", ep);
                raiseEvent(onReconnected, new NEventArgs<>(ep));
            }
        });
    }

    ChannelId channelId() {
        return channel != null ? channel.id() : null;
    }

    @Override
    public synchronized void send(@NonNull Serializable pack) {
        if (!isConnected()) {
            if (isShouldReconnect()) {
                try {
                    FluentWait.newInstance(sendWaitConnectMillis).until(s -> isConnected());
                } catch (TimeoutException e) {
                    throw new ClientDisconnectedException(e);
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
