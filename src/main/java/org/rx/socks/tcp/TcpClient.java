package org.rx.socks.tcp;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.beans.$;
import org.rx.core.*;
import org.rx.core.ManualResetEvent;
import org.rx.socks.Sockets;
import org.rx.socks.tcp.packet.HandshakePacket;

import java.io.Serializable;
import java.util.concurrent.Future;
import java.util.function.BiConsumer;

import static org.rx.beans.$.$;
import static org.rx.core.App.Config;
import static org.rx.core.Contract.require;

@Slf4j
public class TcpClient extends Disposable implements EventTarget<TcpClient> {
    public interface EventNames {
        String Error = "onError";
        String Connected = "onConnected";
        String Disconnected = "onDisconnected";
        String Send = "onSend";
        String Receive = "onReceive";
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
    protected ChannelHandlerContext ctx;
    @Getter
    protected volatile boolean isConnected;
    @Getter
    @Setter
    private volatile boolean autoReconnect;

    public TcpClient(TcpConfig config, HandshakePacket handshake) {
        init(config, handshake);
    }

    protected TcpClient() {
    }

    @SneakyThrows
    protected void init(TcpConfig config, HandshakePacket handshake) {
        require(config, handshake);

        this.config = config;
        this.handshake = handshake;
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
        ManualResetEvent connectWaiter = new ManualResetEvent();
        bootstrap.connect(config.getEndpoint()).addListener(f -> {
            if (!f.isSuccess()) {
                log.debug("Connect {} fail", config.getEndpoint());
            }
            connectWaiter.set();
        }); //不会触发异常
        if (wait) {
            connectWaiter.waitOne(config.getConnectTimeout());
            connectWaiter.reset();
        }
    }

    protected void reconnect() {
        if (!autoReconnect || isConnected) {
            return;
        }

        $<Future> $f = $();
        $f.v = Tasks.schedule(() -> {
            try {
                if (isConnected) {
                    log.debug("Client reconnected");
                    return;
                }
                bootstrap.connect(config.getEndpoint()).addListener(f -> {
                    if (!f.isSuccess()) {
                        log.debug("Connect {} fail", config.getEndpoint());
                    }
                });
            } finally {
                if (!autoReconnect || isConnected) {
                    $f.v.cancel(false);
                }
            }
        }, Config.getScheduleDelay());
    }

    public void send(Serializable pack) {
        require(pack, (Object) isConnected);

        NEventArgs<Serializable> args = new NEventArgs<>(pack);
        raiseEvent(onSend, args);
        if (args.isCancel() || ctx == null) {
            return;
        }
        ctx.writeAndFlush(pack);
        log.debug("ClientWrite {}", ctx.channel().remoteAddress());
    }
}
