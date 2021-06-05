package org.rx.net.socks;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelOption;
import io.netty.handler.codec.socksx.v5.Socks5CommandRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5InitialRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5PasswordAuthRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5ServerEncoder;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Disposable;
import org.rx.core.Strings;
import org.rx.core.exception.InvalidException;
import org.rx.net.MemoryMode;
import org.rx.net.Sockets;
import org.rx.net.socks.upstream.DirectUpstream;

//thanks https://github.com/hsupu/netty-socks
@Slf4j
@RequiredArgsConstructor
public class SocksProxyServer extends Disposable {
    @Getter
    private final SocksConfig config;
    @Getter(AccessLevel.PROTECTED)
    @Setter
    private Authenticator authenticator;
    @Setter
    private FlowLogger flowLogger;
    private ServerBootstrap bootstrap;
    @Getter
    private volatile boolean isStarted;

    public boolean isAuth() {
        return authenticator != null;
    }

    @Override
    protected void freeObjects() {
        Sockets.closeBootstrap(bootstrap);
    }

    public synchronized void start() {
        if (isStarted) {
            throw new InvalidException("Server has started");
        }

        if (config.getUpstreamSupplier() == null) {
            config.setUpstreamSupplier(endpoint -> new DirectUpstream());
        }
        if (flowLogger == null) {
            flowLogger = new FlowLoggerImpl();
        }
        bootstrap = Sockets.serverBootstrap(channel -> {
            //流量统计
            channel.pipeline().addLast(ProxyChannelManageHandler.class.getSimpleName(), new ProxyChannelManageHandler(3000, flowLogger));
            //超时处理
            channel.pipeline().addLast(new IdleStateHandler(config.getReadTimeoutSeconds(), config.getWriteTimeoutSeconds(), 0))
                    .addLast(new ProxyChannelIdleHandler());
//            SocksPortUnificationServerHandler
            channel.pipeline().addLast(Socks5ServerEncoder.DEFAULT)
                    .addLast(Socks5InitialRequestDecoder.class.getSimpleName(), new Socks5InitialRequestDecoder())
                    .addLast(Socks5InitialRequestHandler.class.getSimpleName(), new Socks5InitialRequestHandler(SocksProxyServer.this));
            if (isAuth()) {
                channel.pipeline().addLast(Socks5PasswordAuthRequestDecoder.class.getSimpleName(), new Socks5PasswordAuthRequestDecoder())
                        .addLast(Socks5PasswordAuthRequestHandler.class.getSimpleName(), new Socks5PasswordAuthRequestHandler(SocksProxyServer.this));
            }
            channel.pipeline().addLast(Socks5CommandRequestDecoder.class.getSimpleName(), new Socks5CommandRequestDecoder())
                    .addLast(Socks5CommandRequestHandler.class.getSimpleName(), new Socks5CommandRequestHandler(SocksProxyServer.this));
        }).option(ChannelOption.SO_BACKLOG, config.getBacklog())
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, config.getConnectTimeoutMillis());
        bootstrap.bind(config.getListenPort()).addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess()) {
                log.error("Listened on port {} fail..", config.getListenPort(), f.cause());
                isStarted = false;
            }
        });
        isStarted = true;
    }
}
