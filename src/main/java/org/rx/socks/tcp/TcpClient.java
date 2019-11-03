package org.rx.socks.tcp;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.beans.$;
import org.rx.core.*;
import org.rx.core.ManualResetEvent;
import org.rx.socks.Sockets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.lang.ref.WeakReference;
import java.util.concurrent.Future;
import java.util.function.BiConsumer;

import static org.rx.beans.$.$;
import static org.rx.core.Contract.as;
import static org.rx.core.Contract.require;
import static org.rx.core.ThreadExecutor.TaskFactory;

@Slf4j
public class TcpClient extends Disposable implements EventTarget<TcpClient> {
    public interface EventNames {
        String Error = "onError";
        String Connected = "onConnected";
        String Disconnected = "onDisconnected";
        String Send = "onSend";
        String Receive = "onReceive";
    }

    public static class BaseClientHandler extends ChannelInboundHandlerAdapter {
        protected static final Logger log = LoggerFactory.getLogger(TcpClient.class);
        private WeakReference<TcpClient> weakRef;

        protected TcpClient getClient() {
            TcpClient client = weakRef.get();
            require(client);
            return client;
        }

        public BaseClientHandler(TcpClient client) {
            require(client);
            this.weakRef = new WeakReference<>(client);
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            log.debug("clientActive {}", ctx.channel().remoteAddress());
            TcpClient client = getClient();
            client.channel = ctx;
            client.isConnected = true;
            client.connectWaiter.set();

            NEventArgs<ChannelHandlerContext> args = new NEventArgs<>(ctx);
            client.raiseEvent(client.onConnected, args);
            if (args.isCancel()) {
                Sockets.closeOnFlushed(ctx.channel());
            }
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            log.debug("clientRead {} {}", ctx.channel().remoteAddress(), msg.getClass());

            TcpClient client = getClient();
            Serializable pack;
            if ((pack = as(msg, Serializable.class)) == null) {
                log.debug("channelRead discard {} {}", ctx.channel().remoteAddress(), msg.getClass());
                return;
            }
            client.raiseEvent(client.onReceive, new PackEventArgs<>(ctx, pack));
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            log.debug("clientInactive {}", ctx.channel().remoteAddress());
            TcpClient client = getClient();
            client.channel = null;
            client.isConnected = false;

            NEventArgs<ChannelHandlerContext> args = new NEventArgs<>(ctx);
            client.raiseEvent(client.onDisconnected, args);
            client.reconnect();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.error("clientCaught {}", ctx.channel().remoteAddress(), cause);
            TcpClient client = getClient();
            ErrorEventArgs<ChannelHandlerContext> args = new ErrorEventArgs<>(ctx, cause);
            try {
                client.raiseEvent(client.onError, args);
            } catch (Exception e) {
                log.error("clientCaught", e);
            }
            if (args.isCancel()) {
                return;
            }
            Sockets.closeOnFlushed(ctx.channel());
        }
    }

    public volatile BiConsumer<TcpClient, NEventArgs<ChannelHandlerContext>> onConnected, onDisconnected;
    public volatile BiConsumer<TcpClient, PackEventArgs<ChannelHandlerContext>> onSend, onReceive;
    public volatile BiConsumer<TcpClient, ErrorEventArgs<ChannelHandlerContext>> onError;
    @Getter
    private TcpConfig config;
    @Getter
    private String appId;
    private Bootstrap bootstrap;
    private SslContext sslCtx;
    @Getter(AccessLevel.PROTECTED)
    private ChannelHandlerContext channel;
    private ManualResetEvent connectWaiter;
    @Getter
    private volatile boolean isConnected;
    @Getter
    @Setter
    private volatile boolean autoReconnect;

    public TcpClient(TcpConfig config, String appId) {
        init(config, appId);
    }

    protected TcpClient() {
    }

    @SneakyThrows
    protected void init(TcpConfig config, String appId) {
        require(config, appId);

        this.config = config;
        this.appId = appId;
        connectWaiter = new ManualResetEvent();
    }

    @Override
    protected void freeObjects() {
        autoReconnect = false; //import
        Sockets.closeBootstrap(bootstrap);
        isConnected = false;
    }

    public void connect() {
        connect(false);
    }

    @SneakyThrows
    public void connect(boolean wait) {
        if (isConnected) {
            throw new InvalidOperationException("Client has connected");
        }

        if (config.isEnableSsl()) {
            sslCtx = SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build();
        }
        bootstrap = Sockets.bootstrap(Sockets.channelClass(), null, config.getMemoryMode(), null)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, config.getConnectTimeout())
                .handler(new TcpChannelInitializer(config, sslCtx == null ? null : channel -> sslCtx.newHandler(channel.alloc(), config.getEndpoint().getHostString(), config.getEndpoint().getPort())));
        bootstrap.connect(config.getEndpoint());
        if (wait) {
            connectWaiter.waitOne(config.getConnectTimeout());
            connectWaiter.reset();
        }
    }

    private void reconnect() {
        if (!autoReconnect || isConnected) {
            return;
        }

        $<Future> $f = $();
        $f.v = TaskFactory.schedule(() -> {
            try {
                if (isConnected) {
                    log.debug("Client reconnected");
                    return;
                }
                try {
                    bootstrap.connect(config.getEndpoint());
                    connectWaiter.waitOne(config.getConnectTimeout());
                    connectWaiter.reset();
                } catch (Exception e) {
                    log.error("Client reconnected", e);
                }
            } finally {
                if (!autoReconnect || isConnected) {
                    $f.v.cancel(false);
                }
            }
        }, 2 * 1000);
    }

    public void send(Serializable pack) {
        require(pack, (Object) isConnected);

        PackEventArgs<ChannelHandlerContext> args = new PackEventArgs<>(channel, pack);
        raiseEvent(onSend, args);
        if (args.isCancel()) {
            return;
        }
        channel.writeAndFlush(pack);
    }
}
