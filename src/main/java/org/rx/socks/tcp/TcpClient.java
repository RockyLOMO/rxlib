package org.rx.socks.tcp;

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
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.*;
import org.rx.core.ManualResetEvent;
import org.rx.socks.Sockets;
import org.rx.socks.tcp.packet.ErrorPacket;
import org.rx.socks.tcp.packet.HandshakePacket;

import java.io.Serializable;
import java.net.InetSocketAddress;
import java.util.concurrent.Future;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static org.rx.core.App.Config;
import static org.rx.core.Contract.*;

@Slf4j
public class TcpClient extends Disposable implements EventTarget<TcpClient> {
    public interface EventNames {
        String Error = "onError";
        String Connected = "onConnected";
        String Disconnected = "onDisconnected";
        String Send = "onSend";
        String Receive = "onReceive";
    }

    private class PacketClientHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            log.debug("clientActive {}", ctx.channel().remoteAddress());

            ctx.writeAndFlush(getHandshake()).addListener(p -> {
                if (p.isSuccess()) {
                    Tasks.run(() -> raiseEvent(onConnected, EventArgs.Empty));
                }
            });
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof ErrorPacket) {
                exceptionCaught(ctx, new InvalidOperationException(String.format("Server error message: %s", ((ErrorPacket) msg).getErrorMessage())));
                return;
            }
            log.debug("clientRead {} {}", ctx.channel().remoteAddress(), msg.getClass());

            Serializable pack;
            if ((pack = as(msg, Serializable.class)) == null) {
                log.debug("channelRead discard {} {}", ctx.channel().remoteAddress(), msg.getClass());
                return;
            }
            Tasks.run(() -> raiseEvent(onReceive, new NEventArgs<>(pack)));
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            log.info("clientInactive {}", ctx.channel().remoteAddress());

            raiseEvent(onDisconnected, EventArgs.Empty);
            reconnect();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.error("clientCaught {}", ctx.channel().remoteAddress(), cause);
            if (!ctx.channel().isActive()) {
                return;
            }

            NEventArgs<Throwable> args = new NEventArgs<>(cause);
            try {
                raiseEvent(onError, args);
            } catch (Exception e) {
                log.error("clientCaught", e);
            }
            if (args.isCancel()) {
                return;
            }
            Sockets.closeOnFlushed(ctx.channel());
        }
    }

    public volatile BiConsumer<TcpClient, EventArgs> onConnected, onDisconnected;
    public volatile BiConsumer<TcpClient, NEventArgs<Serializable>> onSend, onReceive;
    public volatile BiConsumer<TcpClient, NEventArgs<Throwable>> onError;
    @Getter
    private TcpConfig config;
    @Getter
    private HandshakePacket handshake;
    private Bootstrap bootstrap;
    private SslContext sslCtx;
    private volatile Channel channel;
    @Getter
    @Setter
    private volatile boolean autoReconnect;
    @Setter
    private Function<InetSocketAddress, InetSocketAddress> preReconnect;
    private volatile Future reconnectFuture;
    private volatile ChannelFuture reconnectChannelFuture;

    public boolean isConnected() {
        return channel != null && channel.isActive();
    }

    protected boolean isShouldReconnect() {
        return autoReconnect && !isConnected();
    }

    public TcpClient(TcpConfig config, HandshakePacket handshake) {
        require(config, handshake);

        this.config = config;
        this.handshake = handshake;
    }

    protected TcpClient() {
    }

    @Override
    protected synchronized void freeObjects() {
        autoReconnect = false; //import
//        Sockets.closeOnFlushed(channel, f -> {
////            sleep(2000);  暂停会有ClosedChannelException
//            Sockets.closeBootstrap(bootstrap);
//        });
        Sockets.closeOnFlushed(channel);
        Sockets.closeBootstrap(bootstrap);
    }

    public void connect() {
        connect(false);
    }

    @SneakyThrows
    public synchronized void connect(boolean wait) {
        if (isConnected()) {
            throw new InvalidOperationException("Client has connected");
        }

        if (config.isEnableSsl()) {
            sslCtx = SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build();
        }
        bootstrap = Sockets.bootstrap(null, config.getMemoryMode(), channel -> {
            ChannelPipeline pipeline = channel.pipeline();
            if (sslCtx != null) {
                pipeline.addLast(sslCtx.newHandler(channel.alloc(), config.getEndpoint().getHostString(), config.getEndpoint().getPort()));
            }
            if (config.isEnableCompress()) {
                pipeline.addLast(ZlibCodecFactory.newZlibEncoder(ZlibWrapper.GZIP));
                pipeline.addLast(ZlibCodecFactory.newZlibDecoder(ZlibWrapper.GZIP));
            }
            pipeline.addLast(new ObjectEncoder(),
                    new ObjectDecoder(ClassResolvers.weakCachingConcurrentResolver(TcpConfig.class.getClassLoader())),
                    new PacketClientHandler());
        }).option(ChannelOption.CONNECT_TIMEOUT_MILLIS, config.getConnectTimeout());
        ChannelFuture future = bootstrap.connect(config.getEndpoint());
        if (!wait) {
            future.addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
            return;
        }
        ManualResetEvent connectWaiter = new ManualResetEvent();
        future.addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess()) {
                log.error("connect {} fail", config.getEndpoint(), f.cause());
                f.channel().close();
                if (autoReconnect) {
                    reconnect(connectWaiter);
                    return;
                }
            }
            channel = f.channel();
            connectWaiter.set();
        });
        connectWaiter.waitOne();
        connectWaiter.reset();
        if (!autoReconnect && !isConnected()) {
            throw new InvalidOperationException("Client connect fail");
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
            log.info("reconnect check..");
            if (!isShouldReconnect() || reconnectChannelFuture != null) {
                return;
            }
            InetSocketAddress ep = preReconnect != null ? preReconnect.apply(config.getEndpoint()) : config.getEndpoint();
            reconnectChannelFuture = bootstrap.connect(ep).addListener((ChannelFutureListener) f -> {
                if (!f.isSuccess()) {
                    log.info("reconnect {} fail", ep);
                    f.channel().close();
                    return;
                }
                log.info("reconnect {} ok", ep);
                channel = f.channel();
                config.setEndpoint(ep);
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
        }, Config.getScheduleDelay());
    }

    public synchronized void send(Serializable pack) {
        require(pack, isConnected());

        NEventArgs<Serializable> args = new NEventArgs<>(pack);
        raiseEvent(onSend, args);
        if (args.isCancel()) {
            return;
        }
        channel.writeAndFlush(pack).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
        log.debug("clientWrite {} {}", getConfig().getEndpoint(), pack);
    }
}
