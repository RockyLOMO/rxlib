package org.rx.net.rpc.impl;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.handler.codec.serialization.ClassResolvers;
import io.netty.handler.codec.serialization.ObjectDecoder;
import io.netty.handler.codec.serialization.ObjectEncoder;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import lombok.Getter;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.bean.DateTime;
import org.rx.bean.RxConfig;
import org.rx.core.*;
import org.rx.core.exception.InvalidException;
import org.rx.net.Sockets;
import org.rx.net.TransportUtil;
import org.rx.net.rpc.*;
import org.rx.net.rpc.packet.ErrorPacket;
import org.rx.net.rpc.packet.HandshakePacket;
import org.rx.net.rpc.packet.PingMessage;

import java.io.Serializable;
import java.net.InetSocketAddress;
import java.util.Date;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;

import static org.rx.core.App.*;

@Slf4j
public class StatefulRpcClient extends Disposable implements RpcClient {
    class ClientHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            Channel channel = ctx.channel();
            log.debug("clientActive {}", channel.remoteAddress());

            ctx.writeAndFlush(new HandshakePacket(config.getEventVersion())).addListener(p -> {
                if (p.isSuccess()) {
                    //握手需要异步
                    raiseEventAsync(onConnected, EventArgs.EMPTY);
                }
            });
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof ErrorPacket) {
                exceptionCaught(ctx, new InvalidException("Server error message: %s", ((ErrorPacket) msg).getErrorMessage()));
                return;
            }

            Channel channel = ctx.channel();
            log.debug("clientRead {} {}", channel.remoteAddress(), msg.getClass());
            Serializable pack;
            if ((pack = as(msg, Serializable.class)) == null) {
                log.debug("clientRead discard {} {}", channel.remoteAddress(), msg.getClass());
                return;
            }
            if (tryAs(pack, PingMessage.class, p -> {
                log.info("clientHeartbeat pong {} {}ms", channel.remoteAddress(), p.getReplyTimestamp() - p.getTimestamp());
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
            reconnect();
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
                        ctx.writeAndFlush(new PingMessage());
                        break;
                }
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            Channel channel = ctx.channel();
            App.log("clientCaught {}", channel.remoteAddress(), cause);
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

    private static final RpcClientConfig NULL_CONF = new RpcClientConfig();
    public volatile BiConsumer<RpcClient, EventArgs> onConnected, onDisconnected;
    public volatile BiConsumer<RpcClient, NEventArgs<InetSocketAddress>> onReconnecting, onReconnected;
    public volatile BiConsumer<RpcClient, NEventArgs<Serializable>> onSend, onReceive;
    public volatile BiConsumer<RpcClient, NEventArgs<PingMessage>> onPong;
    public volatile BiConsumer<RpcClient, NEventArgs<Throwable>> onError;
    @Getter
    private final RpcClientConfig config;
    private Bootstrap bootstrap;
    @Getter
    private Date connectedTime;
    private volatile Channel channel;
    private volatile Future<?> reconnectFuture;
    private volatile ChannelFuture reconnectChannelFuture;

    @Override
    public @NonNull TaskScheduler asyncScheduler() {
        return RpcServer.SCHEDULER;
    }

    public boolean isAutoReconnect() {
        return config.getReconnectPeriod() > 0;
    }

    public boolean isConnected() {
        return channel != null && channel.isActive();
    }

    protected boolean isShouldReconnect() {
        return isAutoReconnect() && !isConnected();
    }

    public InetSocketAddress getLocalAddress() {
        if (!isConnected()) {
            return null;
        }
        return (InetSocketAddress) channel.localAddress();
    }

    public StatefulRpcClient(@NonNull RpcClientConfig config) {
        this.config = config;
//        log.info("reconnect status: {} {}", autoReconnect, isShouldReconnect());
    }

    protected StatefulRpcClient() {
        this.config = NULL_CONF;
    }

    @Override
    protected void freeObjects() {
        config.setReconnectPeriod(RpcClientConfig.NON_RECONNECT); //import
        Sockets.closeOnFlushed(channel);
//        bootstrap.config().group().shutdownGracefully();
    }

    public void connect() {
        connect(false);
    }

    @SneakyThrows
    public synchronized void connect(boolean wait) {
        if (isConnected()) {
            throw new InvalidException("Client has connected");
        }

        bootstrap = Sockets.bootstrap(RpcClientConfig.REACTOR_NAME, config, channel -> {
            ChannelPipeline pipeline = channel.pipeline().addLast(new IdleStateHandler(RpcServerConfig.HEARTBEAT_TIMEOUT, RpcServerConfig.HEARTBEAT_TIMEOUT / 2, 0));
            TransportUtil.addBackendHandler(channel, config, config.getServerEndpoint());
            pipeline.addLast(new ObjectEncoder(),
                    new ObjectDecoder(RxConfig.MAX_HEAP_BUF_SIZE, ClassResolvers.weakCachingConcurrentResolver(RpcServer.class.getClassLoader())),
                    new ClientHandler());
        });
        ChannelFuture future = bootstrap.connect(config.getServerEndpoint());
        if (!wait) {
            future.addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
            return;
        }
        ManualResetEvent connectWaiter = new ManualResetEvent();
        future.addListeners(Sockets.logConnect(config.getServerEndpoint()), (ChannelFutureListener) f -> {
            if (!f.isSuccess()) {
                f.channel().close();
                if (isAutoReconnect()) {
                    reconnect(connectWaiter);
                    return;
                }
            }
            channel = f.channel();
            connectedTime = DateTime.now();
            connectWaiter.set();
        });
        try {
            connectWaiter.waitOne(config.getConnectTimeoutMillis());
            connectWaiter.reset();
        } catch (TimeoutException e) {
            throw new InvalidException("Client connect fail", e);
        }
        if (!isAutoReconnect() && !isConnected()) {
            throw new InvalidException("Client connect %s fail", config.getServerEndpoint());
        }
    }

    protected void reconnect() {
        reconnect(null);
    }

    private synchronized void reconnect(ManualResetEvent mainWaiter) {
        if (!isShouldReconnect() || reconnectFuture != null) {
            return;
        }
        reconnectFuture = Tasks.scheduleUntil(() -> {
            log.info("reconnect {} check..", config.getServerEndpoint());
            if (!isShouldReconnect() || reconnectChannelFuture != null) {
                return;
            }
            NEventArgs<InetSocketAddress> args = new NEventArgs<>(config.getServerEndpoint());
            raiseEvent(onReconnecting, args);
            InetSocketAddress ep = args.getValue();
            reconnectChannelFuture = bootstrap.connect(ep).addListener((ChannelFutureListener) f -> {
                if (!f.isSuccess()) {
                    log.warn("reconnect {} fail", ep);
                    f.channel().close();
                    reconnectChannelFuture = null;
                    return;
                }
                log.info("reconnect {} ok", ep);
                channel = f.channel();
                config.setServerEndpoint(ep);
                connectedTime = DateTime.now();
                raiseEvent(onReconnected, args);
                reconnectChannelFuture = null;
            });
        }, () -> {
            boolean ok = !isShouldReconnect();
            if (ok) {
                if (mainWaiter != null) {
                    mainWaiter.set();
                }
                reconnectFuture = null;
            }
            return ok;
        }, config.getReconnectPeriod());
    }

    @Override
    public synchronized void send(@NonNull Serializable pack) {
        if (!isConnected()) {
            if (reconnectFuture != null) {
                try {
                    FluentWait.newInstance(8000).until(s -> isConnected());
                } catch (TimeoutException e) {
                    throw new ClientDisconnectedException(e);
                }
            }
            if (!isConnected()) {
                throw new ClientDisconnectedException(channel.id());
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
    public boolean hasAttr(String name) {
        return channel.hasAttr(AttributeKey.valueOf(name));
    }

    @Override
    public <T> Attribute<T> attr(String name) {
        return channel.attr(AttributeKey.valueOf(name));
    }
}
