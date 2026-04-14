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

import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

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
    @Getter(AccessLevel.PROTECTED)
    final Authenticator authenticator;
    final ConcurrentMap<Integer, Channel> udpRelayRegistry = new ConcurrentHashMap<>();
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
                EventLoopGroup reactor = Sockets.reactor(Sockets.ReactorNames.SHARED_TCP, true);
                bootstrap = new ServerBootstrap()
//                        .group(new DefaultEventLoopGroup(1), Sockets.reactor(Sockets.ReactorNames.SHARED_TCP, true))
                        .group(reactor, reactor)
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
                tcpChannel = bootstrap.attr(SocksContext.SOCKS_SVR, this).bind(memoryAddr).channel();
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
        if (config.getReadTimeoutSeconds() > 0 || config.getWriteTimeoutSeconds() > 0) {
            pipeline.addLast(ProxyChannelIdleHandler.class.getSimpleName(), new ProxyChannelIdleHandler(config.getReadTimeoutSeconds(), config.getWriteTimeoutSeconds()));
        }
        // SocksPortUnificationServerHandler
        Sockets.addTcpServerHandler(channel, config);
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
        for (Channel relay : udpRelayRegistry.values()) {
            Sockets.closeOnFlushed(relay);
        }
        udpRelayRegistry.clear();
        // 内存模式传入的memoryChannel不释放
        if (bootstrap != null) {
            Sockets.closeOnFlushed(tcpChannel);
        }
        Sockets.closeBootstrap(bootstrap);
    }

    @SneakyThrows
    boolean cipherRoute(UnresolvedEndpoint dstEp) {
        if (cipherRouter == null) {
            return false;
        }
        return cipherRouter.invoke(dstEp);
    }

    void registerUdpRelay(Channel relay) {
        InetSocketAddress local = (InetSocketAddress) relay.localAddress();
        if (local == null) {
            return;
        }
        udpRelayRegistry.put(local.getPort(), relay);
        relay.closeFuture().addListener(f -> udpRelayRegistry.remove(local.getPort(), relay));
    }

    public boolean resetUdpRelay(int relayPort) {
        return withUdpRelay(relayPort, relay -> {
            clearUdpRelayState(relay, null);
            return true;
        });
    }

    public boolean claimUdpRelay(int relayPort, InetSocketAddress clientAddr) {
        return withUdpRelay(relayPort, relay -> {
            clearUdpRelayState(relay, clientAddr);
            return true;
        });
    }

    @SneakyThrows
    boolean withUdpRelay(int relayPort, java.util.concurrent.Callable<Boolean> task, Channel relay) {
        if (relay.eventLoop().inEventLoop()) {
            return task.call();
        }
        return relay.eventLoop().submit(task).get();
    }

    @SneakyThrows
    private boolean withUdpRelay(int relayPort, java.util.function.Function<Channel, Boolean> fn) {
        Channel relay = udpRelayRegistry.get(relayPort);
        if (relay == null || !relay.isActive()) {
            return false;
        }
        return withUdpRelay(relayPort, () -> fn.apply(relay), relay);
    }

    @SuppressWarnings("unchecked")
    private void clearUdpRelayState(Channel relay, InetSocketAddress clientAddr) {
        if (relay.pipeline().get(Udp2rawHandler.class) != null) {
            ConcurrentMap<InetSocketAddress, SocksContext> ctxMap = relay.attr(Udp2rawHandler.ATTR_CTX_MAP).get();
            if (ctxMap != null) {
                ctxMap.clear();
            }
            ConcurrentMap<UnresolvedEndpoint, SocksContext> routeMap = relay.attr(Udp2rawHandler.ATTR_ROUTE_MAP).get();
            if (routeMap != null) {
                routeMap.clear();
            }
            relay.attr(Udp2rawHandler.ATTR_CLIENT_ADDR).set(clientAddr);
        } else {
            ConcurrentMap<InetSocketAddress, SocksContext> ctxMap = relay.attr(SocksUdpRelayHandler.ATTR_CTX_MAP).get();
            if (ctxMap != null) {
                ctxMap.clear();
            }
            ConcurrentMap<UnresolvedEndpoint, SocksContext> routeMap = relay.attr(SocksUdpRelayHandler.ATTR_ROUTE_MAP).get();
            if (routeMap != null) {
                routeMap.clear();
            }
            relay.attr(SocksUdpRelayHandler.ATTR_CLIENT_ADDR).set(clientAddr);
        }
        relay.attr(UdpRelayAttributes.ATTR_CLIENT_LOCKED).set(Boolean.TRUE);
    }

}
