package org.rx.net.rpc.impl;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.handler.codec.serialization.ObjectDecoder;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import lombok.Getter;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Constants;
import org.rx.bean.DateTime;
import org.rx.core.*;
import org.rx.exception.ExceptionHandler;
import org.rx.exception.InvalidException;
import org.rx.net.Sockets;
import org.rx.net.TransportUtil;
import org.rx.net.rpc.*;
import org.rx.net.rpc.protocol.ErrorPacket;
import org.rx.net.rpc.protocol.HandshakePacket;

import java.io.Serializable;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeoutException;

import static org.rx.core.Extends.*;

@Slf4j
public class StatefulRpcClient extends Disposable implements RpcClient {
    class ClientHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            Channel channel = ctx.channel();
            log.debug("clientActive {}", channel.remoteAddress());

            handshakePacket.setEventVersion(config.getEventVersion());
            ctx.writeAndFlush(handshakePacket).addListener(p -> {
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
                log.warn("clientRead discard {} {}", channel.remoteAddress(), msg.getClass());
                return;
            }
            if (tryAs(pack, Long.class, p -> {
                log.info("clientHeartbeat pong {} {}ms", channel.remoteAddress(), System.currentTimeMillis() - p);
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
                        ctx.writeAndFlush(System.currentTimeMillis());
                        break;
                }
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            Channel channel = ctx.channel();
            ExceptionHandler.INSTANCE.log("clientCaught {}", channel.remoteAddress(), cause);
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
    public final Delegate<RpcClient, EventArgs> onConnected = Delegate.create(),
            onDisconnected = Delegate.create();
    public final Delegate<RpcClient, NEventArgs<InetSocketAddress>> onReconnecting = Delegate.create(),
            onReconnected = Delegate.create();
    public final Delegate<RpcClient, NEventArgs<Serializable>> onSend = Delegate.create(),
            onReceive = Delegate.create();
    public final Delegate<RpcClient, NEventArgs<Long>> onPong = Delegate.create();
    public final Delegate<RpcClient, NEventArgs<Throwable>> onError = Delegate.create();
    @Getter
    final RpcClientConfig config;
    @Getter
    final HandshakePacket handshakePacket = new HandshakePacket();
    Bootstrap bootstrap;
    volatile Channel channel;
    volatile InetSocketAddress reconnectingEp;
    //cache meta
    @Getter
    InetSocketAddress remoteEndpoint, localEndpoint;
    @Getter
    DateTime connectedTime;

    @Override
    public @NonNull ThreadPool asyncScheduler() {
        return RpcServer.SCHEDULER;
    }

    public boolean isConnected() {
        return channel != null && channel.isActive();
    }

    protected boolean isShouldReconnect() {
        return config.isEnableReconnect() && !isConnected();
    }

    public StatefulRpcClient(@NonNull RpcClientConfig config) {
        this.config = config;
    }

    protected StatefulRpcClient() {
        this.config = NULL_CONF;
    }

    @Override
    protected void freeObjects() {
        config.setEnableReconnect(false); //import
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

        bootstrap = Sockets.bootstrap(RpcServerConfig.REACTOR_NAME, config, channel -> {
            ChannelPipeline pipeline = channel.pipeline().addLast(new IdleStateHandler(RpcServerConfig.HEARTBEAT_TIMEOUT, RpcServerConfig.HEARTBEAT_TIMEOUT / 2, 0));
            TransportUtil.addBackendHandler(channel, config, config.getServerEndpoint());
            pipeline.addLast(RpcClientConfig.DEFAULT_ENCODER,
                    new ObjectDecoder(Constants.MAX_HEAP_BUF_SIZE, RpcClientConfig.DEFAULT_CLASS_RESOLVER),
                    new ClientHandler());
        });
        ManualResetEvent syncRoot = null;
        doConnect(false, wait ? syncRoot = new ManualResetEvent() : null);
        if (syncRoot == null) {
            return;
        }
        try {
            syncRoot.waitOne(config.getConnectTimeoutMillis());
            syncRoot.reset();
        } catch (TimeoutException e) {
            throw new InvalidException("Client connect fail", e);
        }
        if (!config.isEnableReconnect() && !isConnected()) {
            throw new InvalidException("Client connect %s fail", config.getServerEndpoint());
        }
    }

    synchronized void doConnect(boolean reconnect, ManualResetEvent syncRoot) {
        InetSocketAddress ep;
        if (reconnect) {
            if (!isShouldReconnect()) {
                return;
            }

            NEventArgs<InetSocketAddress> args = new NEventArgs<>(ifNull(reconnectingEp, config.getServerEndpoint()));
            raiseEvent(onReconnecting, args);
            ep = reconnectingEp = args.getValue();
        } else {
            ep = config.getServerEndpoint();
        }

        bootstrap.connect(ep).addListeners(Sockets.logConnect(config.getServerEndpoint()), (ChannelFutureListener) f -> {
            channel = f.channel();
            if (!f.isSuccess()) {
                if (isShouldReconnect()) {
                    Tasks.timer().setTimeout(() -> {
                        doConnect(true, syncRoot);
                        asyncContinue(isShouldReconnect());
                    }, d -> {
                        long delay = d >= 5000 ? 5000 : Math.max(d * 2, 100);
                        log.warn("{} reconnect {} failed will re-attempt in {}ms", this, ep, delay);
                        return delay;
                    }, this, TimeoutFlag.SINGLE);
                } else {
                    log.warn("{} {} fail", reconnect ? "reconnect" : "connect", ep);
                }
                return;
            }
            reconnectingEp = null;
            config.setServerEndpoint(ep);
            remoteEndpoint = (InetSocketAddress) channel.remoteAddress();
            localEndpoint = (InetSocketAddress) channel.localAddress();
            connectedTime = DateTime.now();

            if (syncRoot != null) {
                syncRoot.set();
            }
            if (reconnect) {
                log.info("reconnect {} ok", ep);
                raiseEvent(onReconnected, new NEventArgs<>(ep));
            }
        });
    }

    @Override
    public synchronized void send(@NonNull Serializable pack) {
        if (!isConnected()) {
            if (isShouldReconnect()) {
                try {
                    FluentWait.newInstance(4000).until(s -> isConnected());
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
        if (!isConnected()) {
            throw new ClientDisconnectedException(channel.id());
        }

        return channel.hasAttr(AttributeKey.valueOf(name));
    }

    @Override
    public <T> Attribute<T> attr(String name) {
        if (!isConnected()) {
            throw new ClientDisconnectedException(channel.id());
        }

        return channel.attr(AttributeKey.valueOf(name));
    }
}
