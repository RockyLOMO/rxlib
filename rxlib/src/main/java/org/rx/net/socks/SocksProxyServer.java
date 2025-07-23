package org.rx.net.socks;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.socksx.v5.Socks5CommandRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5InitialRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5PasswordAuthRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5ServerEncoder;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Delegate;
import org.rx.core.Disposable;
import org.rx.core.EventPublisher;
import org.rx.net.MemoryMode;
import org.rx.net.Sockets;
import org.rx.net.socks.upstream.Upstream;
import org.rx.net.support.SocksSupport;
import org.rx.net.support.UnresolvedEndpoint;
import org.rx.util.function.BiAction;
import org.rx.util.function.PredicateFunc;
import org.rx.util.function.TripleAction;

@Slf4j
public class SocksProxyServer extends Disposable implements EventPublisher<SocksProxyServer> {
    public static final TripleAction<SocksProxyServer, SocksContext> DIRECT_ROUTER = (s, e) -> e.setUpstream(new Upstream(e.getFirstDestination()));
    public static final PredicateFunc<UnresolvedEndpoint> DNS_CIPHER_ROUTER = dstEp -> dstEp.getPort() == SocksSupport.DNS_PORT
//            || dstEp.getPort() == 80
            ;
    public final Delegate<SocksProxyServer, SocksContext> onRoute = Delegate.create(DIRECT_ROUTER),
            onUdpRoute = Delegate.create(DIRECT_ROUTER);
    public final Delegate<SocksProxyServer, SocksContext> onReconnecting = Delegate.create();
    @Getter
    final SocksConfig config;
    final ServerBootstrap bootstrap;
    final Channel tcpChannel;
    final Channel udpChannel;
    @Getter(AccessLevel.PROTECTED)
    final Authenticator authenticator;
    //只有压缩时一定要用
    @Setter
    private PredicateFunc<UnresolvedEndpoint> cipherRouter;

    public boolean isBind() {
        return tcpChannel.isActive();
    }

//    public Integer getBindPort() {
//        InetSocketAddress ep = (InetSocketAddress) tcpChannel.localAddress();
//        return ep != null ? ep.getPort() : null;
//    }

    public boolean isAuthEnabled() {
        return authenticator != null;
    }

    public SocksProxyServer(SocksConfig config) {
        this(config, null, null);
    }

    public SocksProxyServer(@NonNull SocksConfig config, Authenticator authenticator) {
        this(config, authenticator, null);
    }

    public SocksProxyServer(@NonNull SocksConfig config, Authenticator authenticator, BiAction<Channel> onBind) {
        this.config = config;
        this.authenticator = authenticator;
        bootstrap = Sockets.serverBootstrap(config, channel -> {
            ChannelPipeline pipeline = channel.pipeline();
            if (isAuthEnabled()) {
                //Traffic statistics
                pipeline.addLast(ProxyManageHandler.class.getSimpleName(), new ProxyManageHandler(config.getTrafficShapingInterval()));
            }
            pipeline.addLast(ProxyChannelIdleHandler.class.getSimpleName(), new ProxyChannelIdleHandler(config.getReadTimeoutSeconds(), config.getWriteTimeoutSeconds()));
//            SocksPortUnificationServerHandler
            Sockets.addServerHandler(channel, config);
            pipeline.addLast(Socks5ServerEncoder.DEFAULT)
                    .addLast(Socks5InitialRequestDecoder.class.getSimpleName(), new Socks5InitialRequestDecoder())
                    .addLast(Socks5InitialRequestHandler.class.getSimpleName(), Socks5InitialRequestHandler.DEFAULT);
            if (isAuthEnabled()) {
                pipeline.addLast(Socks5PasswordAuthRequestDecoder.class.getSimpleName(), new Socks5PasswordAuthRequestDecoder())
                        .addLast(Socks5PasswordAuthRequestHandler.class.getSimpleName(), Socks5PasswordAuthRequestHandler.DEFAULT);
            }
            pipeline.addLast(Socks5CommandRequestDecoder.class.getSimpleName(), new Socks5CommandRequestDecoder())
                    .addLast(Socks5CommandRequestHandler.class.getSimpleName(), Socks5CommandRequestHandler.DEFAULT);
        });
        tcpChannel = bootstrap.attr(SocksContext.SOCKS_SVR, this).bind(Sockets.newAnyEndpoint(config.getListenPort())).addListeners(Sockets.logBind(config.getListenPort()), (ChannelFutureListener) f -> {
            if (f.isSuccess() && onBind != null) {
                onBind.accept(f.channel());
            }
        }).channel();
        tcpChannel.closeFuture().addListener(f -> {
            log.warn("S5 close on {}", config.getListenPort());
        });

        //udp server
        int udpPort = config.getListenPort();
        udpChannel = Sockets.udpBootstrap(Sockets.ReactorNames.SS, MemoryMode.HIGH, channel -> {
            ChannelPipeline pipeline = channel.pipeline();
            if (config.isEnableUdp2raw()) {
                pipeline.addLast(Udp2rawHandler.DEFAULT);
            } else {
                Sockets.addServerHandler(channel, config);
                pipeline.addLast(Socks5UdpRelayHandler.DEFAULT);
            }
        }).attr(SocksContext.SOCKS_SVR, this).bind(Sockets.newAnyEndpoint(udpPort)).addListener(Sockets.logBind(config.getListenPort())).channel();
    }

    @Override
    protected void freeObjects() {
        Sockets.closeOnFlushed(tcpChannel);
        Sockets.closeBootstrap(bootstrap);
        udpChannel.close();
    }

    @SneakyThrows
    boolean cipherRoute(UnresolvedEndpoint dstEp) {
        if (cipherRouter == null) {
            return false;
        }
        return cipherRouter.invoke(dstEp);
    }
}
