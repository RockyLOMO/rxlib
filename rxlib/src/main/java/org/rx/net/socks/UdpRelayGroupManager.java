package org.rx.net.socks;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelOption;
import io.netty.util.AttributeKey;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Disposable;
import org.rx.core.Tasks;
import org.rx.diagnostic.DiagnosticMetrics;
import org.rx.net.SocketConfig;
import org.rx.net.Sockets;
import org.rx.net.udp.DailyUdpWire;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.BindException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
final class UdpRelayGroupManager extends Disposable {
    private static final AttributeKey<UdpRelayEntry> ATTR_RELAY_GROUP_ENTRY =
            AttributeKey.valueOf("udpRelayGroupEntry");
    private static final int MAX_GROUPS = 4096;
    private static final int BIND_TIMEOUT_MILLIS = 5000;

    private final AtomicInteger nextPort = new AtomicInteger();
    final SocksProxyServer server;
    final ConcurrentMap<String, UdpRelayGroup> groups = new ConcurrentHashMap<>();
    final ConcurrentMap<Integer, UdpRelayEntry> relays = new ConcurrentHashMap<>();
    final ScheduledFuture<?> cleanupTask;

    UdpRelayGroupManager(SocksProxyServer server) {
        this.server = server;
        cleanupTask = Tasks.schedulePeriod(this::cleanupIdleGroups, 30_000L);
    }

    SocksRpcCapabilities capabilities() {
        SocksConfig config = server.getConfig();
        SocksRpcCapabilities caps = SocksRpcCapabilities.udpRelayGroup(config.getUdpRelayControlMaxRelaysPerGroup(), MAX_GROUPS);
        if (config.isDailyUdp() && caps.getMaxRelaysPerGroup() >= 2) {
            caps.addFlag(SocksRpcCapabilities.SUPPORT_DAILY_UDP);
        }
        return caps;
    }

    UdpRelayGroupOpenResult open(UdpRelayGroupOpenRequest request) {
        checkNotClosed();
        if (request == null) {
            return UdpRelayGroupOpenResult.fail("BAD_REQUEST", "request is null");
        }
        if (request.isSharedDedupRequired()) {
            return UdpRelayGroupOpenResult.fail("UNSUPPORTED_SHARED_DEDUP", "shared dedup is not enabled");
        }
        if (groups.size() >= MAX_GROUPS) {
            return UdpRelayGroupOpenResult.fail("TOO_MANY_GROUPS", "too many udp relay groups");
        }

        SocksConfig config = server.getConfig();
        int maxAllowed = Math.max(1, config.getUdpRelayControlMaxRelaysPerGroup());
        int initialCount = clamp(request.getInitialRelayCount(), 1, maxAllowed);
        int minActive = clamp(request.getMinActiveRelays(), 1, initialCount);
        int maxRelayCount = clamp(request.getMaxRelayCount(), initialCount, maxAllowed);
        int features = request.getDailyFeatures();
        boolean daily = features != 0;
        if (daily) {
            if ((features & config.getDailyUdpFeatures()) != features) {
                return UdpRelayGroupOpenResult.fail("DAILY_DISABLED", "daily UDP profile is disabled");
            }
            if ((features & DailyUdpWire.AES) != 0 && (request.getClientSalt() == null || !DailyUdpWire.hasCipherKey())) {
                return UdpRelayGroupOpenResult.fail("DAILY_AES_UNAVAILABLE", "UDP key and session salt required");
            }
            if (maxAllowed < 2 || request.getMaxRelayCount() < 2) {
                return UdpRelayGroupOpenResult.fail("DAILY_HOPS_UNAVAILABLE", "two relay ports required");
            }
            initialCount = minActive = maxRelayCount = 2;
        }
        long now = System.currentTimeMillis();
        long idleMillis = request.getIdleTimeoutMillis() > 0L
                ? Math.max(1000L, request.getIdleTimeoutMillis())
                : config.getUdpRelayGroupIdleMillis();

        UdpRelayGroup group = new UdpRelayGroup(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                daily ? null : request.getClientAddr(), request.getFirstDestination(), maxRelayCount, idleMillis, now, features, request.getClientSalt());
        groups.put(group.groupId, group);

        List<UdpRelayEndpoint> opened = addRelays(group, initialCount);
        if (opened.size() < minActive || group.closed) {
            closeGroup(group, "open-fail");
            DiagnosticMetrics.record("socks.udp.relay.group.open.count", 1D,
                    "result=fail,reason=min-active");
            return UdpRelayGroupOpenResult.fail("MIN_ACTIVE_NOT_MET",
                    "active=" + opened.size() + ", minActive=" + minActive);
        }

        DiagnosticMetrics.record("socks.udp.relay.group.open.count", 1D,
                "result=success");
        recordState("open");
        return UdpRelayGroupOpenResult.success(group.groupId, group.dataPlaneToken, group.expireAtMillis(), opened);
    }

    UdpRelayGroupUpdateResult addUdpRelays(String groupId, int count) {
        checkNotClosed();
        UdpRelayGroup group = groups.get(groupId);
        if (group == null || group.closed) {
            DiagnosticMetrics.record("socks.udp.relay.group.add.count", 1D,
                    "result=fail,reason=missing");
            return UdpRelayGroupUpdateResult.fail("GROUP_NOT_FOUND", "group not found");
        }
        if (group.daily) {
            return UdpRelayGroupUpdateResult.success(group.expireAtMillis(), Collections.<UdpRelayEndpoint>emptyList());
        }
        int allowed = Math.max(0, group.maxRelayCount - group.relays.size());
        int addCount = Math.min(Math.max(0, count), allowed);
        if (addCount <= 0) {
            return UdpRelayGroupUpdateResult.success(group.expireAtMillis(), Collections.<UdpRelayEndpoint>emptyList());
        }
        List<UdpRelayEndpoint> endpoints = addRelays(group, addCount);
        DiagnosticMetrics.record("socks.udp.relay.group.add.count", 1D,
                "result=" + (endpoints.isEmpty() ? "fail" : "success"));
        recordState("add");
        return UdpRelayGroupUpdateResult.success(group.expireAtMillis(), endpoints);
    }

    boolean removeUdpRelay(String groupId, int relayPort) {
        UdpRelayGroup group = groups.get(groupId);
        if (group == null || group.closed) {
            DiagnosticMetrics.record("socks.udp.relay.group.remove.count", 1D,
                    "result=fail,reason=missing");
            return false;
        }
        if (group.daily) {
            return false;
        }
        UdpRelayEntry entry = group.relays.remove(relayPort);
        if (entry == null) {
            DiagnosticMetrics.record("socks.udp.relay.group.remove.count", 1D,
                    "result=fail,reason=relay-missing");
            return false;
        }
        relays.remove(relayPort, entry);
        Sockets.closeOnFlushed(entry.udpChannel);
        DiagnosticMetrics.record("socks.udp.relay.group.remove.count", 1D,
                "result=success");
        recordState("remove");
        return true;
    }

    boolean heartbeat(String groupId) {
        UdpRelayGroup group = groups.get(groupId);
        if (group == null || group.closed) {
            DiagnosticMetrics.record("socks.udp.relay.group.heartbeat.count", 1D,
                    "result=fail");
            return false;
        }
        group.touch();
        DiagnosticMetrics.record("socks.udp.relay.group.heartbeat.count", 1D,
                "result=success");
        return true;
    }

    boolean close(String groupId) {
        UdpRelayGroup group = groups.get(groupId);
        if (group == null) {
            DiagnosticMetrics.record("socks.udp.relay.group.close.count", 1D,
                    "result=fail,reason=missing");
            return false;
        }
        closeGroup(group, "rpc-close");
        return true;
    }

    private List<UdpRelayEndpoint> addRelays(UdpRelayGroup group, int count) {
        List<UdpRelayEndpoint> endpoints = new ArrayList<>(count);
        for (int i = 0; i < count && !group.closed && group.relays.size() < group.maxRelayCount; i++) {
            if (group.daily && group.primary != null && server.getConfig().isRedirectUdpHops()) {
                UdpRelayEndpoint primary = endpoints.get(0);
                InetSocketAddress address = primary.getRelayAddress();
                endpoints.add(new UdpRelayEndpoint(SocksRpcCapabilities.DAILY_HOP_ID,
                        new InetSocketAddress(address.getAddress(), address.getPort() + 1), 1, group.expireAtMillis()));
                break;
            }
            UdpRelayEntry entry = bindRelay(group);
            if (entry != null) {
                endpoints.add(entry.endpoint());
            }
        }
        return endpoints;
    }

    private UdpRelayEntry bindRelay(UdpRelayGroup group) {
        Channel relay = null;
        try {
            SocksConfig config = server.getConfig();
            SocketAddress udpBindAddr = Socks5CommandRequestHandler.resolveUdpRelayBindAddress(config.getInetListenAddress());
            Channel primary = group.primary;
            boolean alias = group.daily && primary != null;
            Bootstrap bootstrap = Sockets.udpBootstrap(group.daily ? dailySocketConfig(config) : config, ch -> {
                ChannelPipeline p = ch.pipeline();
                if (group.daily) {
                    if (alias) {
                        p.addLast(group.wire.aliasHandler());
                        return;
                    }
                    group.wire = DailyUdpWire.server(group.dataPlaneToken, sender -> {
                        ConcurrentMap<InetSocketAddress, SocksContext> routes = ch.attr(SocksUdpRelayHandler.ATTR_CTX_MAP).get();
                        return routes != null && routes.containsKey(sender);
                    }, group.features, group.clientSalt);
                    p.addLast(group.wire);
                } else if (config.getUdpReadTimeoutSeconds() > 0 || config.getUdpWriteTimeoutSeconds() > 0) {
                    p.addLast(new ProxyChannelIdleHandler(config.getUdpReadTimeoutSeconds(), config.getUdpWriteTimeoutSeconds()));
                }
                p.addLast(SocksUdpRelayHandler.DEFAULT);
            }).attr(SocksContext.SOCKS_SVR, server);
            if (alias) {
                bootstrap = bootstrap.clone(primary.eventLoop());
            }
            ChannelFuture future = group.daily ? bindDaily(bootstrap, (InetSocketAddress) udpBindAddr, config)
                    : bootstrap.bind(udpBindAddr);
            relay = future.channel();
            relay.attr(SocksUdpRelayHandler.ATTR_CLIENT_ADDR).set(group.clientAddr);
            relay.attr(UdpRelayAttributes.ATTR_CLIENT_ORIGIN_ADDR).set(group.clientAddr);
            relay.attr(UdpRelayAttributes.ATTR_CLIENT_LOCKED).set(group.clientAddr != null);
            boolean redundantClientPeer = !group.daily && UdpRelayAttributes.shouldTrackClientAsRedundantPeer(config);
            if (redundantClientPeer) {
                UdpRelayAttributes.initRedundantPeers(relay);
                if (group.clientAddr != null) {
                    UdpRelayAttributes.addRedundantClientPeerIfChanged(relay, group.clientAddr);
                }
            }
            relay.attr(UdpRelayAttributes.ATTR_REDUNDANT_CLIENT_PEER).set(redundantClientPeer);

            if (!future.awaitUninterruptibly(BIND_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) || !future.isSuccess()) {
                Throwable cause = future.cause();
                if (cause != null) {
                    log.warn("bind rpc udp relay group {} fail", group.groupId, cause);
                }
                Sockets.closeOnFlushed(relay);
                return null;
            }

            InetSocketAddress udpBindLocalAddr = (InetSocketAddress) relay.localAddress();
            InetSocketAddress advertise = Socks5CommandRequestHandler.resolveUdpRelayAdvertiseAddress(
                    config.getInetListenAddress(), udpBindLocalAddr);
            if (group.daily && !alias) {
                group.primary = relay;
            }
            UdpRelayEntry entry = new UdpRelayEntry(alias ? SocksRpcCapabilities.DAILY_HOP_ID : UUID.randomUUID().toString(),
                    group, relay, advertise);
            relay.attr(ATTR_RELAY_GROUP_ENTRY).set(entry);
            group.relays.put(udpBindLocalAddr.getPort(), entry);
            relays.put(udpBindLocalAddr.getPort(), entry);
            server.registerUdpRelay(relay);
            relay.closeFuture().addListener((ChannelFutureListener) f -> {
                group.relays.remove(udpBindLocalAddr.getPort(), entry);
                relays.remove(udpBindLocalAddr.getPort(), entry);
                if (group.daily && !group.closed) {
                    closeGroup(group, "daily-channel-close");
                } else if (group.closed && group.relays.isEmpty()) {
                    groups.remove(group.groupId, group);
                }
            });
            if (group.closed) {
                relay.close();
                return null;
            }
            return entry;
        } catch (Throwable e) {
            log.warn("bind rpc udp relay group {} error", group.groupId, e);
            if (relay != null) {
                Sockets.closeOnFlushed(relay);
            }
            return null;
        }
    }

    private ChannelFuture bindDaily(Bootstrap bootstrap, InetSocketAddress bind, SocksConfig config) throws BindException {
        bootstrap.option(ChannelOption.SO_REUSEADDR, false);
        int first = config.getDailyPortFirst();
        if (first == 0) {
            return bootstrap.bind(bind);
        }
        first += 2; // First pair serves all adaptive TCP streams.
        int stride = config.isRedirectUdpHops() ? 2 : 1;
        int size = (config.getDailyPortLast() - first + 1) / stride;
        if (size <= 0) {
            throw new BindException("No UDP relay ports after the adaptive TCP pair");
        }
        int start = Math.floorMod(nextPort.getAndIncrement(), size);
        int attempts = 0;
        for (int i = 0; i < size && attempts < 32; i++) {
            int port = first + ((start + i) % size) * stride;
            if (relays.containsKey(port)) {
                continue;
            }
            attempts++;
            ChannelFuture bindFuture = bootstrap.bind(new InetSocketAddress(bind.getAddress(), port));
            if (!bindFuture.awaitUninterruptibly(BIND_TIMEOUT_MILLIS) || !(bindFuture.cause() instanceof BindException)) {
                return bindFuture;
            }
            bindFuture.channel().close();
        }
        throw new BindException("No available daily UDP port in configured range");
    }

    // Preserve socket budgets without installing legacy frame decoders before the daily wire header.
    private static SocketConfig dailySocketConfig(SocksConfig source) {
        if (!UdpRedundantSupport.isConfigured(source) && !source.isUdpCompressEnabled() && source.getUdpResilience() == null) {
            return source;
        }
        SocksConfig config = new SocksConfig();
        config.setTcpAsyncDnsMode(source.getTcpAsyncDnsMode());
        config.setDebug(source.isDebug());
        config.setReactorName(source.getReactorName());
        config.setOptimalSettings(source.getOptimalSettings());
        config.setUdpMtu(source.getUdpMtu());
        config.setUdpWriteLimitBytes(source.getUdpWriteLimitBytes());
        config.setUdpWritePerSourceLimitBytes(source.getUdpWritePerSourceLimitBytes());
        return config;
    }

    private void cleanupIdleGroups() {
        long now = System.currentTimeMillis();
        for (UdpRelayGroup group : groups.values()) {
            if (!group.closed && now - group.lastActiveAtMillis >= group.idleTimeoutMillis) {
                closeGroup(group, "timeout");
            }
        }
    }

    private void closeGroup(UdpRelayGroup group, String reason) {
        if (group == null || group.closed) {
            return;
        }
        group.closed = true;
        groups.remove(group.groupId, group);
        for (UdpRelayEntry entry : group.relays.values()) {
            relays.remove(entry.relayPort, entry);
            Sockets.closeOnFlushed(entry.udpChannel);
        }
        group.relays.clear();
        DiagnosticMetrics.record("socks.udp.relay.group.close.count", 1D,
                "result=" + ("timeout".equals(reason) ? "timeout" : "success") + ",reason=" + reason);
        recordState("close");
    }

    private void recordState(String action) {
        DiagnosticMetrics.record("socks.udp.relay.group.active.count", groups.size(), "action=" + action);
        DiagnosticMetrics.record("socks.udp.relay.group.relay.count", relays.size(), "action=" + action);
    }

    static boolean encrypted(Channel relay) {
        UdpRelayEntry entry = relay.attr(ATTR_RELAY_GROUP_ENTRY).get();
        return entry != null && (entry.group.features & DailyUdpWire.AES) != 0;
    }

    static void touch(Channel relay) {
        UdpRelayEntry entry = relay != null ? relay.attr(ATTR_RELAY_GROUP_ENTRY).get() : null;
        if (entry != null) {
            entry.group.touch();
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    protected void dispose() {
        cleanupTask.cancel(false);
        for (UdpRelayGroup group : groups.values()) {
            closeGroup(group, "dispose");
        }
        groups.clear();
        relays.clear();
    }

    static final class UdpRelayGroup {
        final String groupId;
        // Negotiated daily frames carry this per-group cookie. RPC authorization is independent.
        final String dataPlaneToken;
        final boolean daily;
        final int features;
        final UUID clientSalt;
        volatile Channel primary;
        volatile DailyUdpWire wire;
        final InetSocketAddress clientAddr;
        final InetSocketAddress firstDestination;
        final int maxRelayCount;
        final long idleTimeoutMillis;
        final long createdAtMillis;
        final ConcurrentMap<Integer, UdpRelayEntry> relays = new ConcurrentHashMap<>();
        volatile long lastActiveAtMillis;
        volatile boolean closed;

        UdpRelayGroup(String groupId, String dataPlaneToken, InetSocketAddress clientAddr,
                InetSocketAddress firstDestination, int maxRelayCount, long idleTimeoutMillis, long now, int features, UUID clientSalt) {
            this.features = features;
            this.clientSalt = clientSalt;
            daily = features != 0;
            this.groupId = groupId;
            this.dataPlaneToken = dataPlaneToken;
            this.clientAddr = clientAddr;
            this.firstDestination = firstDestination;
            this.maxRelayCount = maxRelayCount;
            this.idleTimeoutMillis = idleTimeoutMillis;
            this.createdAtMillis = now;
            this.lastActiveAtMillis = now;
        }

        long expireAtMillis() {
            return lastActiveAtMillis + idleTimeoutMillis;
        }

        void touch() {
            lastActiveAtMillis = System.currentTimeMillis();
        }
    }

    static final class UdpRelayEntry {
        final String relayId;
        final UdpRelayGroup group;
        final int relayPort;
        final Channel udpChannel;
        final InetSocketAddress relayAddress;
        volatile int weight = 1;

        UdpRelayEntry(String relayId, UdpRelayGroup group, Channel udpChannel, InetSocketAddress relayAddress) {
            this.relayId = relayId;
            this.group = group;
            this.udpChannel = udpChannel;
            this.relayAddress = relayAddress;
            this.relayPort = relayAddress.getPort();
        }

        UdpRelayEndpoint endpoint() {
            return new UdpRelayEndpoint(relayId, relayAddress, weight, group.expireAtMillis());
        }
    }
}
