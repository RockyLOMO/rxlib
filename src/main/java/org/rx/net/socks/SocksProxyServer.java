package org.rx.net.socks;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.socksx.v5.Socks5CommandRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5InitialRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5PasswordAuthRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5ServerEncoder;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.AttributeKey;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Disposable;
import org.rx.core.EventTarget;
import org.rx.net.MemoryMode;
import org.rx.net.Sockets;
import org.rx.net.TransportUtil;
import org.rx.net.support.SocksSupport;
import org.rx.net.support.UnresolvedEndpoint;
import org.rx.net.socks.upstream.DirectUpstream;
import org.rx.net.socks.upstream.Upstream;
import org.rx.util.function.BiFunc;
import org.rx.util.function.PredicateFunc;

import java.util.function.BiConsumer;

@Slf4j
public class SocksProxyServer extends Disposable implements EventTarget<SocksProxyServer> {
    public static final AttributeKey<SocksProxyServer> CTX_SERVER = AttributeKey.valueOf("CTX_SERVER");
    public static final BiFunc<UnresolvedEndpoint, Upstream> DIRECT_ROUTER = DirectUpstream::new;
    public static final PredicateFunc<UnresolvedEndpoint> DNS_AES_ROUTER = dstEp -> dstEp.getPort() == SocksSupport.DNS_PORT
//            || dstEp.getPort() == 80
            ;
    public volatile BiConsumer<SocksProxyServer, ReconnectingEventArgs> onReconnecting;

    @Getter
    final SocksConfig config;
    final ServerBootstrap bootstrap;
    final Channel udpChannel;
    @Getter(AccessLevel.PROTECTED)
    final Authenticator authenticator;
    final BiFunc<UnresolvedEndpoint, Upstream> router;
    @Setter
    private PredicateFunc<UnresolvedEndpoint> aesRouter;

    public boolean isAuthEnabled() {
        return authenticator != null;
    }

    public SocksProxyServer(SocksConfig config) {
        this(config, null, null);
    }

    public SocksProxyServer(@NonNull SocksConfig config, Authenticator authenticator, BiFunc<UnresolvedEndpoint, Upstream> router) {
        if (router == null) {
            router = DIRECT_ROUTER;
        }

        this.config = config;
        this.authenticator = authenticator;
        this.router = router;
        bootstrap = Sockets.serverBootstrap(config, channel -> {
            channel.attr(CTX_SERVER).set(SocksProxyServer.this);
            ChannelPipeline pipeline = channel.pipeline();
            if (isAuthEnabled()) {
                //流量统计
                pipeline.addLast(ProxyManageHandler.class.getSimpleName(), new ProxyManageHandler(authenticator, config.getTrafficShapingInterval()));
            }
            //超时处理
            pipeline.addLast(new IdleStateHandler(config.getReadTimeoutSeconds(), config.getWriteTimeoutSeconds(), 0),
                    ProxyChannelIdleHandler.DEFAULT);
//            SocksPortUnificationServerHandler
            TransportUtil.addFrontendHandler(channel, config);
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
        bootstrap.bind(config.getListenPort()).addListener(Sockets.logBind(config.getListenPort()));

        //udp server
        udpChannel = Sockets.udpBootstrap(true, MemoryMode.HIGH).handler(new ChannelInitializer<NioDatagramChannel>() {
            @Override
            protected void initChannel(NioDatagramChannel channel) {
                channel.attr(CTX_SERVER).set(SocksProxyServer.this);
                ChannelPipeline pipeline = channel.pipeline();
                TransportUtil.addFrontendHandler(channel, config);
                pipeline.addLast(Socks5UdpRelayHandler.DEFAULT);
            }
        }).bind(config.getListenPort()).addListener(Sockets.logBind(config.getListenPort())).channel();
    }

    @Override
    protected void freeObjects() {
        Sockets.closeBootstrap(bootstrap);
        udpChannel.close();
    }

    @SneakyThrows
    boolean aesRouter(UnresolvedEndpoint dstEp) {
        if (aesRouter == null) {
            return false;
        }
        return aesRouter.invoke(dstEp);
    }
}
