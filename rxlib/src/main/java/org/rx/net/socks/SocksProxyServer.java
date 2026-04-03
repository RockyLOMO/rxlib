package org.rx.net.socks;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalServerChannel;
import io.netty.handler.codec.socksx.v5.Socks5CommandRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5InitialRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5PasswordAuthRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5ServerEncoder;
import lombok.*;
import org.rx.core.Delegate;
import org.rx.core.Disposable;
import org.rx.core.EventPublisher;
import org.rx.exception.InvalidException;
import org.rx.net.Sockets;
import org.rx.net.socks.upstream.Upstream;
import org.rx.net.support.UnresolvedEndpoint;
import org.rx.util.function.BiAction;
import org.rx.util.function.PredicateFunc;
import org.rx.util.function.TripleAction;

// @Slf4j
public class SocksProxyServer extends Disposable implements EventPublisher<SocksProxyServer> {
    public static final TripleAction<SocksProxyServer, SocksContext> DIRECT_ROUTER = (s, e) -> e.setUpstream(new Upstream(e.getFirstDestination()));
    public static final PredicateFunc<UnresolvedEndpoint> DNS_CIPHER_ROUTER = dstEp -> dstEp.getPort() == SocksRpcContract.DNS_PORT
            || dstEp.getPort() == 80;
    public final Delegate<SocksProxyServer, SocksContext> onTcpRoute = Delegate.create(DIRECT_ROUTER),
            onUdpRoute = Delegate.create(DIRECT_ROUTER);
    public final Delegate<SocksProxyServer, SocksContext> onReconnecting = Delegate.create();
    @Getter
    final SocksConfig config;
    final ServerBootstrap bootstrap;
    final Channel tcpChannel;
    final Channel udpChannel;
    @Getter(AccessLevel.PROTECTED)
    final Authenticator authenticator;
    // 只有压缩时一定要用
    @Setter
    private PredicateFunc<UnresolvedEndpoint> cipherRouter;

    public boolean isBind() {
        return tcpChannel.isActive();
    }

    // public Integer getBindPort() {
    // InetSocketAddress ep = (InetSocketAddress) tcpChannel.localAddress();
    // return ep != null ? ep.getPort() : null;
    // }

    public boolean isAuthEnabled() {
        return authenticator != null;
    }

    public SocksProxyServer(SocksConfig config) {
        this(config, null);
    }

    public SocksProxyServer(SocksConfig config, Authenticator authenticator) {
        this(config, authenticator, (BiAction<Channel>) null);
    }

    public SocksProxyServer(SocksConfig config, Authenticator authenticator, BiAction<Channel> onBind) {
        this(config, authenticator, onBind, false, null);
    }

    public SocksProxyServer(SocksConfig config, Authenticator authenticator, boolean enableMemoryChannel, Channel memoryChannel) {
        this(config, authenticator, null, enableMemoryChannel, memoryChannel);
    }

    private SocksProxyServer(@NonNull SocksConfig config, Authenticator authenticator, BiAction<Channel> onBind,
                             boolean enableMemoryChannel, Channel memoryChannel) {
        this.config = config;
        this.authenticator = authenticator;

        if (enableMemoryChannel) {
            if (memoryChannel == null) {
                bootstrap = new ServerBootstrap()
                        .group(new DefaultEventLoopGroup(1), Sockets.reactor(Sockets.ReactorNames.SHARED_TCP, true))
                        .channel(LocalServerChannel.class)
                        .childHandler(new ChannelInitializer<Channel>() {
                            @Override
                            protected void initChannel(Channel ch) {
                                acceptChannel(ch);
                            }
                        });
                LocalAddress memoryAddr = config.getMemoryAddress();
                if (memoryAddr == null) {
                    memoryAddr = new LocalAddress(this.getClass());
                }
                tcpChannel = bootstrap.attr(SocksContext.SOCKS_SVR, this).bind(memoryAddr).syncUninterruptibly().channel();
            } else {
                if (!memoryChannel.isActive()) {
                    throw new InvalidException("memoryChannel not active");
                }
                acceptChannel(memoryChannel);
                bootstrap = null;
                tcpChannel = memoryChannel;
            }
        } else {
            bootstrap = Sockets.serverBootstrap(config, this::acceptChannel);
            tcpChannel = bootstrap.attr(SocksContext.SOCKS_SVR, this).bind(Sockets.newAnyEndpoint(config.getListenPort())).addListener((ChannelFutureListener) f -> {
                if (f.isSuccess() && onBind != null) {
                    onBind.accept(f.channel());
                }
            }).channel();
        }

        // udp server
        int udpPort = config.getListenPort();
        udpChannel = Sockets.udpBootstrap(config, channel -> {
            ChannelPipeline pipeline = channel.pipeline();
            if (config.isEnableUdp2raw()) {
                pipeline.addLast(Udp2rawHandler.DEFAULT);
            } else {
                Sockets.addServerHandler(channel, config);
                pipeline.addLast(SocksUdpRelayHandler.DEFAULT);
            }
        }).attr(SocksContext.SOCKS_SVR, this).bind(Sockets.newAnyEndpoint(udpPort)).channel();
    }

    private void acceptChannel(Channel channel) {
        if (channel.attr(SocksContext.SOCKS_SVR).get() != null) {
            return;
        }
        ChannelPipeline pipeline = channel.pipeline();
        if (isAuthEnabled()) {
            // Traffic statistics
            pipeline.addLast(ProxyManageHandler.class.getSimpleName(), new ProxyManageHandler(config.getTrafficShapingInterval()));
        }
        pipeline.addLast(ProxyChannelIdleHandler.class.getSimpleName(), new ProxyChannelIdleHandler(config.getReadTimeoutSeconds(), config.getWriteTimeoutSeconds()));
        // SocksPortUnificationServerHandler
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
        channel.attr(SocksContext.SOCKS_SVR).set(this);
    }

    @Override
    protected void dispose() {
        // 内存模式传入的memoryChannel不释放
        if (bootstrap != null) {
            Sockets.closeOnFlushed(tcpChannel);
        }
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
