package org.rx.net.rpc.impl;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.handler.codec.compression.ZlibCodecFactory;
import io.netty.handler.codec.compression.ZlibWrapper;
import io.netty.handler.codec.serialization.ClassResolvers;
import io.netty.handler.codec.serialization.ObjectDecoder;
import io.netty.handler.codec.serialization.ObjectEncoder;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.bean.DateTime;
import org.rx.core.*;
import org.rx.core.exception.InvalidException;
import org.rx.net.Sockets;
import org.rx.net.rpc.RpcClient;
import org.rx.net.rpc.RpcClientConfig;
import org.rx.net.rpc.RpcServer;
import org.rx.net.rpc.RpcServerConfig;
import org.rx.net.rpc.packet.ErrorPacket;
import org.rx.net.rpc.packet.HandshakePacket;

import java.io.Serializable;
import java.net.InetSocketAddress;
import java.util.Date;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;

import static org.rx.core.App.*;

@Slf4j
public class StatefulRpcClient extends Disposable implements RpcClient {
    class Handler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            log.debug("clientActive {}", ctx.channel().remoteAddress());

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
            log.debug("clientRead {} {}", ctx.channel().remoteAddress(), msg.getClass());

            Serializable pack;
            if ((pack = as(msg, Serializable.class)) == null) {
                log.debug("channelRead discard {} {}", ctx.channel().remoteAddress(), msg.getClass());
                return;
            }
            raiseEventAsync(onReceive, new NEventArgs<>(pack));
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            log.info("clientInactive {}", ctx.channel().remoteAddress());

            raiseEvent(onDisconnected, EventArgs.EMPTY);
            reconnect();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            App.log("clientCaught {}", ctx.channel().remoteAddress(), cause);
            if (!ctx.channel().isActive()) {
                return;
            }

            NEventArgs<Throwable> args = new NEventArgs<>(cause);
            quietly(() -> raiseEvent(onError, args));
            if (args.isCancel()) {
                return;
            }
            Sockets.closeOnFlushed(ctx.channel());
        }
    }

    public volatile BiConsumer<RpcClient, EventArgs> onConnected, onDisconnected;
    public volatile BiConsumer<RpcClient, NEventArgs<InetSocketAddress>> onReconnecting, onReconnected;
    public volatile BiConsumer<RpcClient, NEventArgs<Serializable>> onSend, onReceive;
    public volatile BiConsumer<RpcClient, NEventArgs<Throwable>> onError;
    @Getter
    private final RpcClientConfig config;
    private Bootstrap bootstrap;
    private SslContext sslCtx;
    @Getter
    private Date connectedTime;
    private volatile Channel channel;
    @Getter
    @Setter
    private volatile boolean autoReconnect;
    private volatile Future<?> reconnectFuture;
    private volatile ChannelFuture reconnectChannelFuture;

    public boolean isConnected() {
        return channel != null && channel.isActive();
    }

    protected boolean isShouldReconnect() {
        return autoReconnect && !isConnected();
    }

    public InetSocketAddress getLocalAddress() {
        if (!isConnected()) {
            return null;
        }
        return (InetSocketAddress) channel.localAddress();
    }

    public StatefulRpcClient(@NonNull RpcClientConfig config) {
        this.config = config;
        autoReconnect = config.isAutoReconnect();
//        log.info("reconnect status: {} {}", autoReconnect, isShouldReconnect());
    }

    protected StatefulRpcClient() {
        this.config = null;
    }

    @Override
    protected synchronized void freeObjects() {
        autoReconnect = false; //import
        Sockets.closeOnFlushed(channel);
//        Sockets.closeBootstrap(bootstrap);
    }

    public void connect() {
        connect(false);
    }

    @SneakyThrows
    public synchronized void connect(boolean wait) {
        if (isConnected()) {
            throw new InvalidException("Client has connected");
        }

        if (config.isEnableSsl()) {
            sslCtx = SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build();
        }
        bootstrap = Sockets.bootstrap(Sockets.sharedEventLoop(this.getClass().getSimpleName()), config.getMemoryMode(), channel -> {
            ChannelPipeline pipeline = channel.pipeline();
            if (sslCtx != null) {
                pipeline.addLast(sslCtx.newHandler(channel.alloc(), config.getServerEndpoint().getHostString(), config.getServerEndpoint().getPort()));
            }
            if (config.isEnableCompress()) {
                pipeline.addLast(ZlibCodecFactory.newZlibEncoder(ZlibWrapper.GZIP));
                pipeline.addLast(ZlibCodecFactory.newZlibDecoder(ZlibWrapper.GZIP));
            }
            pipeline.addLast(new ObjectEncoder(),
                    new ObjectDecoder(RpcServerConfig.MAX_OBJECT_SIZE, ClassResolvers.weakCachingConcurrentResolver(RpcServer.class.getClassLoader())),
                    new Handler());
        }).option(ChannelOption.CONNECT_TIMEOUT_MILLIS, config.getConnectTimeoutMillis());
        ChannelFuture future = bootstrap.connect(config.getServerEndpoint());
        if (!wait) {
            future.addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
            return;
        }
        ManualResetEvent connectWaiter = new ManualResetEvent();
        future.addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess()) {
                log.error("connect {} fail", config.getServerEndpoint(), f.cause());
                f.channel().close();
                if (autoReconnect) {
                    reconnect(connectWaiter);
                    return;
                }
            }
            channel = f.channel();
            connectedTime = DateTime.now();
            connectWaiter.set();
        });
        connectWaiter.waitOne(config.getConnectTimeoutMillis());
        connectWaiter.reset();
        if (!autoReconnect && !isConnected()) {
            throw new InvalidException("Client connect fail");
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
                    log.info("reconnect {} fail", ep);
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
        }, App.getConfig().getNetReconnectPeriod());
    }

    @Override
    public synchronized void send(@NonNull Serializable pack) {
        if (!isConnected()) {
            if (reconnectFuture != null) {
                try {
                    FluentWait.newInstance(8000).until(s -> isConnected());
                } catch (TimeoutException e) {
                    throw new InvalidException("Client has disconnected", e);
                }
            }
            if (!isConnected()) {
                throw new InvalidException("Client has disconnected");
            }
        }

        NEventArgs<Serializable> args = new NEventArgs<>(pack);
        raiseEvent(onSend, args);
        if (args.isCancel()) {
            return;
        }
        channel.writeAndFlush(pack).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
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
