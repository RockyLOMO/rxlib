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
import org.rx.diagnostic.DiagnosticMetrics;
import org.rx.exception.InvalidException;
import org.rx.net.Sockets;
import org.rx.net.socks.upstream.Upstream;
import java.net.InetSocketAddress;
import org.rx.util.function.BiAction;
import org.rx.util.function.PredicateFunc;
import org.rx.util.function.TripleAction;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

// @Slf4j
public class SocksProxyServer extends Disposable implements EventPublisher<SocksProxyServer> {
    public static final TripleAction<SocksProxyServer, SocksContext> DIRECT_ROUTER = (s, e) -> e.setUpstream(new Upstream(e.getFirstDestination()));
    public static final PredicateFunc<InetSocketAddress> DNS_CIPHER_ROUTER = dstEp -> dstEp.getPort() == SocksRpcContract.DNS_PORT
            || dstEp.getPort() == 80;
    private static final long UDP_RELAY_OPERATION_TIMEOUT_SECONDS = 5L;
    public final Delegate<SocksProxyServer, SocksContext> onTcpRoute = Delegate.create(DIRECT_ROUTER),
            onUdpRoute = Delegate.create(DIRECT_ROUTER);
    public final Delegate<SocksProxyServer, SocksContext> onReconnecting = Delegate.create();
    @Getter
    final SocksConfig config;
    final ServerBootstrap bootstrap;
    final List<Channel> tcpChannels;
    @Getter(AccessLevel.PROTECTED)
    final Authenticator authenticator;
    final ConcurrentMap<Integer, Channel> udpRelayRegistry = new ConcurrentHashMap<>();
    private final UdpRelayGroupManager udpRelayGroupManager;
    private final Udp2rawServerEntryManager udp2rawEntryManager;
    final AtomicInteger activeChannels = new AtomicInteger();
    // 只有压缩时一定要用
    @Setter
    private PredicateFunc<InetSocketAddress> cipherRouter;
    @Getter
    @Setter
    private Function<String, AuthResult> connectionTagResolver;

    public boolean isBind() {
        for (Channel channel : tcpChannels) {
            if (channel.isActive()) {
                return true;
            }
        }
        return false;
    }

    public int activeChannelCount() {
        return activeChannels.get();
    }

    // public Integer getBindPort() {
    // InetSocketAddress ep = (InetSocketAddress) tcpChannel.localAddress();
    // return ep != null ? ep.getPort() : null;
    // }

    public boolean isAuthEnabled() {
        return authenticator != null;
    }

    boolean isTrafficBindingEnabled() {
        return authenticator != null || connectionTagResolver != null;
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
        this.udpRelayGroupManager = new UdpRelayGroupManager(this);
        this.udp2rawEntryManager = config.isEnableUdp2raw() && config.getUdp2rawClient() == null
                ? new Udp2rawServerEntryManager(this) : null;

        if (enableMemoryChannel) {
            if (memoryChannel == null) {
                LocalAddress memoryAddr = config.getMemoryAddress();
                if (memoryAddr == null) {
                    memoryAddr = new LocalAddress(this.getClass());
                }
                bootstrap = createMemoryServer().bootstrap;
                tcpChannels = Collections.singletonList(bootstrap.attr(SocksContext.SOCKS_SVR, this).bind(memoryAddr).syncUninterruptibly().channel());
            } else {
                if (!memoryChannel.isActive()) {
                    throw new InvalidException("memoryChannel not active");
                }
                acceptChannel(memoryChannel);
                bootstrap = null;
                tcpChannels = Collections.singletonList(memoryChannel);
            }
        } else {
            SocketAddress listenAddress = config.getListenAddress();
            if (listenAddress == null) {
                listenAddress = Sockets.newAnyEndpoint(0);
            }
            if (listenAddress instanceof LocalAddress) {
                bootstrap = createMemoryServer().bootstrap;
                tcpChannels = Collections.singletonList(bootstrap.attr(SocksContext.SOCKS_SVR, this).bind(listenAddress).syncUninterruptibly().channel());
                if (onBind != null) {
                    onBind.accept(tcpChannels.get(0));
                }
            } else {
                bootstrap = Sockets.serverBootstrap(config, this::acceptChannel);
                tcpChannels = Sockets.bindChannels(bootstrap.attr(SocksContext.SOCKS_SVR, this), listenAddress, config);
                if (onBind != null) {
                    for (Channel channel : tcpChannels) {
                        onBind.accept(channel);
                    }
                }
            }
        }

        if (udp2rawEntryManager != null) {
            udp2rawEntryManager.start();
        }
    }

    private MemoryBind createMemoryServer() {
        EventLoopGroup reactor = Sockets.localReactor(Sockets.ReactorNames.SHARED_LOCAL);
        ServerBootstrap localBootstrap = new ServerBootstrap()
                .group(reactor, reactor)
                .channel(LocalServerChannel.class)
                .childHandler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        acceptChannel(ch);
                    }
                });
        return new MemoryBind(localBootstrap);
    }

    @RequiredArgsConstructor
    static final class MemoryBind {
        final ServerBootstrap bootstrap;
    }

    private void acceptChannel(Channel channel) {
        if (channel.attr(SocksContext.SOCKS_SVR).get() != null) {
            return;
        }
        ChannelPipeline pipeline = channel.pipeline();
        if (isTrafficBindingEnabled()) {
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
        activeChannels.incrementAndGet();
        channel.closeFuture().addListener(f -> activeChannels.updateAndGet(v -> v > 0 ? v - 1 : 0));
    }

    @Override
    protected void dispose() {
        if (udp2rawEntryManager != null) {
            udp2rawEntryManager.close();
        }
        udpRelayGroupManager.close();
        for (Channel relay : udpRelayRegistry.values().toArray(new Channel[0])) {
            Sockets.closeOnFlushed(relay);
        }
        udpRelayRegistry.clear();
        // 内存模式传入的memoryChannel不释放
        if (bootstrap != null) {
            for (Channel channel : tcpChannels) {
                Sockets.closeOnFlushed(channel);
            }
        }
        Sockets.closeBootstrap(bootstrap);
    }

    @SneakyThrows
    boolean cipherRoute(InetSocketAddress dstEp) {
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
        int relayPort = local.getPort();
        while (true) {
            Channel previous = udpRelayRegistry.putIfAbsent(relayPort, relay);
            if (previous == null) {
                break;
            }
            if (previous == relay) {
                return;
            }
            if (previous.isActive()) {
                Sockets.closeOnFlushed(relay);
                if (DiagnosticMetrics.isEnabled()) {
                    DiagnosticMetrics.record("socks.udp.relay.duplicate.count", 1D, "action=reject");
                }
                return;
            }
            if (udpRelayRegistry.replace(relayPort, previous, relay)) {
                break;
            }
        }
        if (DiagnosticMetrics.isEnabled()) {
            DiagnosticMetrics.record("socks.udp.relay.active.count", udpRelayRegistry.size(), "action=register");
        }
        relay.closeFuture().addListener(f -> {
            udpRelayRegistry.remove(relayPort, relay);
            if (DiagnosticMetrics.isEnabled()) {
                DiagnosticMetrics.record("socks.udp.relay.active.count", udpRelayRegistry.size(), "action=close");
            }
        });
    }

    public boolean resetUdpRelay(int relayPort) {
        if (DiagnosticMetrics.isEnabled()) {
            DiagnosticMetrics.record("socks.udp.relay.reset.count", 1D, "action=reset");
        }
        return withUdpRelay(relayPort, relay -> {
            clearUdpRelayState(relay, null);
            return true;
        });
    }

    public boolean resetUdpRelay(int relayPort, String token) {
        validateRpcToken(token, "reset");
        return resetUdpRelay(relayPort);
    }

    public boolean claimUdpRelay(int relayPort, InetSocketAddress clientAddr) {
        if (DiagnosticMetrics.isEnabled()) {
            DiagnosticMetrics.record("socks.udp.relay.claim.count", 1D, "action=claim");
        }
        return withUdpRelay(relayPort, relay -> {
            clearUdpRelayState(relay, clientAddr);
            return true;
        });
    }

    public boolean claimUdpRelay(int relayPort, InetSocketAddress clientAddr, String token) {
        validateRpcToken(token, "claim");
        return claimUdpRelay(relayPort, clientAddr);
    }

    public SocksRpcCapabilities socksRpcCapabilities() {
        SocksRpcCapabilities capabilities = udpRelayGroupManager.capabilities();
        if (udp2rawEntryManager != null) {
            capabilities.addFlag(SocksRpcCapabilities.UDP2RAW_TUNNEL);
            capabilities.setUdp2rawFixedEntry(true);
            capabilities.setMaxUdp2rawSessions(config.getUdp2rawMaxSessions());
        }
        return capabilities;
    }

    public SocksRpcCapabilities socksRpcCapabilities(String token) {
        validateRpcToken(token, "capabilities");
        return socksRpcCapabilities();
    }

    public UdpRelayGroupOpenResult openUdpRelayGroup(UdpRelayGroupOpenRequest request) {
        return udpRelayGroupManager.open(request);
    }

    public UdpRelayGroupOpenResult openUdpRelayGroup(UdpRelayGroupOpenRequest request, String token) {
        validateRpcToken(token, "open-group");
        UdpRelayGroupOpenResult result = openUdpRelayGroup(request);
        if (result != null && result.isSuccess()) {
            result.setToken(token);
        }
        return result;
    }

    public UdpRelayGroupUpdateResult addUdpRelays(String groupId, int count) {
        return udpRelayGroupManager.addUdpRelays(groupId, count);
    }

    public UdpRelayGroupUpdateResult addUdpRelays(String groupId, int count, String token) {
        validateRpcToken(token, "add-relay");
        return addUdpRelays(groupId, count);
    }

    public boolean removeUdpRelay(String groupId, int relayPort) {
        return udpRelayGroupManager.removeUdpRelay(groupId, relayPort);
    }

    public boolean removeUdpRelay(String groupId, int relayPort, String token) {
        validateRpcToken(token, "remove-relay");
        return removeUdpRelay(groupId, relayPort);
    }

    public boolean heartbeatUdpRelayGroup(String groupId) {
        return udpRelayGroupManager.heartbeat(groupId);
    }

    public boolean heartbeatUdpRelayGroup(String groupId, String token) {
        validateRpcToken(token, "heartbeat");
        return heartbeatUdpRelayGroup(groupId);
    }

    public boolean closeUdpRelayGroup(String groupId) {
        return udpRelayGroupManager.close(groupId);
    }

    public boolean closeUdpRelayGroup(String groupId, String token) {
        validateRpcToken(token, "close-group");
        return closeUdpRelayGroup(groupId);
    }

    public Udp2rawOpenResult openUdp2rawTunnel(Udp2rawOpenRequest request) {
        if (udp2rawEntryManager == null) {
            return Udp2rawOpenResult.unsupported();
        }
        return udp2rawEntryManager.open(request);
    }

    public Udp2rawOpenResult openUdp2rawTunnel(Udp2rawOpenRequest request, String token) {
        validateRpcToken(token, "open-udp2raw");
        return openUdp2rawTunnel(request);
    }

    public boolean heartbeatUdp2rawTunnel(String tunnelId) {
        return udp2rawEntryManager != null && udp2rawEntryManager.heartbeat(tunnelId);
    }

    public boolean heartbeatUdp2rawTunnel(String tunnelId, String token) {
        validateRpcToken(token, "heartbeat-udp2raw");
        return heartbeatUdp2rawTunnel(tunnelId);
    }

    public boolean closeUdp2rawTunnel(String tunnelId) {
        return udp2rawEntryManager != null && udp2rawEntryManager.closeTunnel(tunnelId);
    }

    public boolean closeUdp2rawTunnel(String tunnelId, String token) {
        validateRpcToken(token, "close-udp2raw");
        return closeUdp2rawTunnel(tunnelId);
    }

    public InetSocketAddress getUdp2rawEntryAddress() {
        return udp2rawEntryManager != null ? udp2rawEntryManager.entryAddress() : null;
    }

    Udp2rawTunnelContext udp2rawTunnelContext(String tunnelId) {
        return udp2rawEntryManager != null ? udp2rawEntryManager.context(tunnelId) : null;
    }

    private void validateRpcToken(String token, String action) {
        try {
            SocksRpcContract.requireValidRpcToken(token);
        } catch (SecurityException e) {
            if (DiagnosticMetrics.isEnabled()) {
                DiagnosticMetrics.record("socks.rpc.auth.fail.count", 1D, "action=" + action);
            }
            throw e;
        }
    }

    @SneakyThrows
    boolean withUdpRelay(int relayPort, java.util.concurrent.Callable<Boolean> task, Channel relay) {
        if (relay.eventLoop().inEventLoop()) {
            return task.call();
        }
        java.util.concurrent.Future<Boolean> future = relay.eventLoop().submit(task);
        try {
            return future.get(UDP_RELAY_OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            future.cancel(false);
            if (DiagnosticMetrics.isEnabled()) {
                DiagnosticMetrics.record("socks.udp.relay.operation.timeout.count", 1D, "action=event-loop");
            }
            return false;
        }
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
        InetSocketAddress lockedClientAddr = isConcreteClientAddress(clientAddr) ? clientAddr : null;
        if (relay.pipeline().get(Udp2rawHandler.class) != null) {
            ConcurrentMap<InetSocketAddress, SocksContext> ctxMap = relay.attr(Udp2rawHandler.ATTR_CTX_MAP).get();
            if (ctxMap != null) {
                ctxMap.clear();
            }
            ConcurrentMap<InetSocketAddress, SocksContext> routeMap = relay.attr(Udp2rawHandler.ATTR_ROUTE_MAP).get();
            if (routeMap != null) {
                routeMap.clear();
            }
            relay.attr(Udp2rawHandler.ATTR_CLIENT_ADDR).set(lockedClientAddr);
        } else {
            SocksUdpRelayHandler.clearRelayState(relay);
            relay.attr(SocksUdpRelayHandler.ATTR_CLIENT_ADDR).set(lockedClientAddr);
        }
        relay.attr(UdpRelayAttributes.ATTR_CLIENT_ORIGIN_ADDR).set(null);
        relay.attr(UdpRelayAttributes.ATTR_CLIENT_LOCKED).set(lockedClientAddr != null);
    }

    private static boolean isConcreteClientAddress(InetSocketAddress clientAddr) {
        return clientAddr != null
                && clientAddr.getAddress() != null
                && !clientAddr.getAddress().isAnyLocalAddress();
    }
}
