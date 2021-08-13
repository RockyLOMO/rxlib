package org.rx.net.socks;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.socksx.v5.Socks5CommandRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5InitialRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5PasswordAuthRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5ServerEncoder;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.*;
import org.rx.core.Disposable;
import org.rx.core.EventTarget;
import org.rx.core.ShellExecutor;
import org.rx.core.Strings;
import org.rx.net.MemoryMode;
import org.rx.net.Sockets;
import org.rx.net.TransportUtil;
import org.rx.net.support.SocksSupport;
import org.rx.net.support.UnresolvedEndpoint;
import org.rx.net.socks.upstream.Upstream;
import org.rx.util.function.PredicateFunc;

import java.util.function.BiConsumer;

import static org.rx.core.App.tryClose;

public class SocksProxyServer extends Disposable implements EventTarget<SocksProxyServer> {
    public static final BiConsumer<SocksProxyServer, RouteEventArgs> DIRECT_ROUTER = (s, e) -> e.setValue(new Upstream(e.getDestinationEndpoint()));
    public static final PredicateFunc<UnresolvedEndpoint> DNS_AES_ROUTER = dstEp -> dstEp.getPort() == SocksSupport.DNS_PORT
//            || dstEp.getPort() == 80
            ;
    public volatile BiConsumer<SocksProxyServer, RouteEventArgs> onRoute = DIRECT_ROUTER, onUdpRoute = DIRECT_ROUTER;
    public volatile BiConsumer<SocksProxyServer, RouteEventArgs> onReconnecting;

    @Getter
    final SocksConfig config;
    final ServerBootstrap bootstrap;
    final Channel udpChannel;
    ShellExecutor udpTun;
    @Getter(AccessLevel.PROTECTED)
    final Authenticator authenticator;
    @Setter
    private PredicateFunc<UnresolvedEndpoint> aesRouter;

    public boolean isAuthEnabled() {
        return authenticator != null;
    }

    public SocksProxyServer(SocksConfig config) {
        this(config, null);
    }

    public SocksProxyServer(@NonNull SocksConfig config, Authenticator authenticator) {
        this.config = config;
        this.authenticator = authenticator;
        bootstrap = Sockets.serverBootstrap(config, channel -> {
            SocksContext.server(channel, SocksProxyServer.this);
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
        int udpPort = config.getListenPort();
        udpChannel = Sockets.udpBootstrap(MemoryMode.HIGH, channel -> {
            SocksContext.server(channel, SocksProxyServer.this);
            ChannelPipeline pipeline = channel.pipeline();
            if (config.isEnableUdp2raw()) {
                pipeline.addLast(Udp2rawHandler.DEFAULT);
            } else {
                TransportUtil.addFrontendHandler(channel, config);
                pipeline.addLast(Socks5UdpRelayHandler.DEFAULT);
            }
        }).bind(Sockets.anyEndpoint(udpPort)).addListener(Sockets.logBind(config.getListenPort())).channel();

        String udpTunnelPwd = config.getUdpTunnelPassword();
        if (!Strings.isEmpty(udpTunnelPwd)) {
            udpTun = new ShellExecutor(String.format("./udp2raw_amd64 -s -l0.0.0.0:%s -r127.0.0.1:%s -k \"%s\" --raw-mode faketcp --cipher-mode xor --auth-mode simple -a",
                    udpPort - 1, udpPort, udpTunnelPwd))
                    .start(ShellExecutor.CONSOLE_OUT);
        }
    }

    @Override
    protected void freeObjects() {
        tryClose(udpTun);
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
