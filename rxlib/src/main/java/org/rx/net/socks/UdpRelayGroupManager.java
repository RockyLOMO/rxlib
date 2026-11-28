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
import org.rx.net.rss.udp.RssDatagramHandler;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.BindException;
import org.rx.net.rss.RssUdpMtuBudget;
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
    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();
    private static final int PROFILES = RssDatagramHandler.GAME | RssDatagramHandler.WEAK;
    static final int MAX_GROUPS = 4096;
    static final int DEFAULT_MAX_GROUPS_PER_CLIENT = 256;
    static final int DEFAULT_MAX_TOTAL_RELAYS = MAX_GROUPS * 2;
    private static final int MAX_IDEMPOTENT_ENTRIES = 1024;
    private static final long IDEMPOTENT_TTL_MILLIS = 15_000L;
    private static final int BIND_TIMEOUT_MILLIS = 5000;

    private final AtomicInteger nextPort = new AtomicInteger();
    final SocksProxyServer server;
    final ConcurrentMap<String, UdpRelayGroup> groups = new ConcurrentHashMap<>();
    final ConcurrentMap<Integer, UdpRelayEntry> relays = new ConcurrentHashMap<>();
    private final AtomicInteger reservedGroups = new AtomicInteger();
    private final AtomicInteger reservedRelays = new AtomicInteger();
    private final ConcurrentMap<String, AtomicInteger> clientGroupCounts = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, IdempotentOpen> idempotentOpens = new ConcurrentHashMap<>();
    volatile int maxGroups = MAX_GROUPS;
    volatile int maxGroupsPerClient = DEFAULT_MAX_GROUPS_PER_CLIENT;
    volatile int maxTotalRelays = DEFAULT_MAX_TOTAL_RELAYS;
    final ScheduledFuture<?> cleanupTask;
    private final RssFixedUdpEntry fixedEntry = new RssFixedUdpEntry();
    private final Channel fixedChannel;

    UdpRelayGroupManager(SocksProxyServer server) {
        this.server = server;
        SocksConfig config = server.getConfig();
        if (config.getFixedUdpPort() > 0) {
            InetSocketAddress listen = config.getInetListenAddress();
            if (!config.isDailyUdp() || listen == null || config.getFixedUdpPort() >= config.getDailyPortFirst()
                    && config.getFixedUdpPort() <= config.getDailyPortLast()) {
                throw new IllegalArgumentException("fixedUdpPort needs an enhanced IP listener and a port outside udpHopPorts");
            }
            fixedChannel = Sockets.udpBootstrap(dailySocketConfig(config), ch -> ch.pipeline().addLast(fixedEntry))
                    .option(ChannelOption.SO_REUSEADDR, false)
                    .bind(new InetSocketAddress(listen.getAddress(), config.getFixedUdpPort())).syncUninterruptibly().channel();
            fixedChannel.config().setRecvByteBufAllocator(new io.netty.channel.FixedRecvByteBufAllocator(65536));
        } else {
            fixedChannel = null;
        }
        cleanupTask = Tasks.schedulePeriod(this::cleanupIdleGroups, 30_000L);
    }

    SocksRpcCapabilities capabilities() {
        SocksConfig config = server.getConfig();
        SocksRpcCapabilities caps = SocksRpcCapabilities.udpRelayGroup(config.getUdpRelayControlMaxRelaysPerGroup(), maxGroups);
        if (fixedChannel != null && fixedChannel.isActive()) caps.addFlag(SocksRpcCapabilities.FIXED_DATAGRAM_ENTRY);
        if (config.isDailyUdp() && (!config.isUdpHop() || caps.getMaxRelaysPerGroup() >= 2)) {
            caps.addFlag(SocksRpcCapabilities.SUPPORT_DAILY_UDP);
            caps.addFlag(SocksRpcCapabilities.SUPPORT_NO_HOP_UDP);
            caps.addFlag(SocksRpcCapabilities.SUPPORT_UDP_FRAG);
            if ((config.getDailyUdpFeatures() & PROFILES) != 0) caps.addFlag(SocksRpcCapabilities.PROFILE_UDP_V1);
        }
        return caps;
    }

    int reservedGroupCount() {
        return reservedGroups.get();
    }

    int reservedRelayCount() {
        return reservedRelays.get();
    }

    int clientGroupCount(String clientId) {
        AtomicInteger c = clientGroupCounts.get(normalizeClientKey(clientId));
        return c == null ? 0 : c.get();
    }

    UdpRelayGroupOpenResult open(UdpRelayGroupOpenRequest request) {
        checkNotClosed();
        if (request == null) {
            return UdpRelayGroupOpenResult.fail("BAD_REQUEST", "request is null");
        }
        if (request.isSharedDedupRequired()) {
            return UdpRelayGroupOpenResult.fail("UNSUPPORTED_SHARED_DEDUP", "shared dedup is not enabled");
        }

        String clientKey = normalizeClientKey(request.getClientId());
        String reqId = request.getClientRequestId();
        String idempKey = reqId != null && !reqId.trim().isEmpty() ? clientKey + "|" + reqId.trim() : null;
        if (idempKey != null) {
            IdempotentOpen prev = idempotentOpens.get(idempKey);
            if (prev != null) {
                UdpRelayGroup existing = groups.get(prev.groupId);
                if (existing != null && !existing.closed && System.currentTimeMillis() - prev.createdAtMillis <= IDEMPOTENT_TTL_MILLIS) {
                    existing.touch();
                    return prev.result;
                }
                idempotentOpens.remove(idempKey, prev);
            }
        }

        SocksConfig config = server.getConfig();
        int maxAllowed = Math.max(1, config.getUdpRelayControlMaxRelaysPerGroup());
        int initialCount = clamp(request.getInitialRelayCount(), 1, maxAllowed);
        int minActive = clamp(request.getMinActiveRelays(), 1, initialCount);
        int maxRelayCount = clamp(request.getMaxRelayCount(), initialCount, maxAllowed);
        int features = request.getDailyFeatures();
        boolean daily = features != 0;
        if (request.isFixedEntry() && (!daily || fixedChannel == null || !fixedChannel.isActive()
                || request.getMaxRelayCount() != 1)) {
            return UdpRelayGroupOpenResult.fail("FIXED_ENTRY_UNAVAILABLE", "fixed ingress requires a single enhanced relay");
        }
        boolean profiled = (features & PROFILES) != 0;
        boolean frag = daily && features != RssDatagramHandler.AES && request.isUdpFragSupported();
        int negotiatedMtu = 0;
        if (request.getProfileVersion() != (profiled ? 1 : 0)
                || profiled && request.getClientSalt() == null
                || (features & RssDatagramHandler.GAME) != 0 && (features & (RssDatagramHandler.WEAK | RssDatagramHandler.FEC)) != 0
                || (features & RssDatagramHandler.WEAK) != 0 && (features & RssDatagramHandler.FEC) == 0) {
            return UdpRelayGroupOpenResult.fail("PROFILE_UNSUPPORTED", "unsupported UDP profile or version");
        }
        if (daily) {
            if ((features & config.getDailyUdpFeatures()) != features) {
                return UdpRelayGroupOpenResult.fail("DAILY_DISABLED", "daily UDP profile is disabled");
            }
            if ((features & RssDatagramHandler.AES) != 0 && (request.getClientSalt() == null || !RssDatagramHandler.hasCipherKey())) {
                return UdpRelayGroupOpenResult.fail("DAILY_AES_UNAVAILABLE", "UDP key and session salt required");
            }
            int serverMtu = config.resolveDailyUdpMtu();
            if (!RssUdpMtuBudget.valid(request.getUdpMtu()) || !RssUdpMtuBudget.valid(serverMtu)) {
                return UdpRelayGroupOpenResult.fail("INVALID_UDP_MTU", "invalid UDP payload budget");
            }
            negotiatedMtu = RssUdpMtuBudget.negotiate(serverMtu, request.getUdpMtu());
            boolean hop = features != RssDatagramHandler.AES && config.isUdpHop() && request.getMaxRelayCount() >= 2;
            if (hop) {
                if (maxAllowed < 2) {
                    return UdpRelayGroupOpenResult.fail("DAILY_HOPS_UNAVAILABLE", "two relay ports required");
                }
                initialCount = minActive = maxRelayCount = 2;
            } else {
                initialCount = minActive = maxRelayCount = 1;
            }
        }

        // Atomic capacity reservation BEFORE allocating group state or binding any UDP socket.
        if (!tryReserve(reservedGroups, 1, maxGroups)) {
            return UdpRelayGroupOpenResult.fail("TOO_MANY_GROUPS", "too many udp relay groups");
        }
        if (!tryReserveClient(clientKey, maxGroupsPerClient)) {
            reservedGroups.decrementAndGet();
            return UdpRelayGroupOpenResult.fail("TOO_MANY_CLIENT_GROUPS", "client udp relay group quota exceeded");
        }

        long now = System.currentTimeMillis();
        long idleMillis = request.getIdleTimeoutMillis() > 0L
                ? Math.max(1000L, request.getIdleTimeoutMillis())
                : config.getUdpRelayGroupIdleMillis();

        UdpRelayGroup group = new UdpRelayGroup(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                clientKey, daily ? null : request.getClientAddr(), request.getFirstDestination(), maxRelayCount, idleMillis, now,
                features, request.getClientSalt(), frag, negotiatedMtu, request.isFixedEntry());
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
        UdpRelayGroupOpenResult result = UdpRelayGroupOpenResult.success(group.groupId, group.dataPlaneToken, group.expireAtMillis(), opened);
        if (daily) {
            result.setFixedEntry(request.isFixedEntry());
            result.setUdpFragEnabled(frag);
            result.setUdpMtu(negotiatedMtu);
        }
        if (profiled) {
            result.setProfileVersion(1);
            result.setSelectedFeatures(features);
        }
        if (group.authSecret != null) {
            result.setAuthSecret(group.authSecret);
        }
        if (idempKey != null) {
            if (idempotentOpens.size() >= MAX_IDEMPOTENT_ENTRIES) {
                pruneIdempotentOpens(now);
            }
            if (idempotentOpens.size() < MAX_IDEMPOTENT_ENTRIES) {
                idempotentOpens.put(idempKey, new IdempotentOpen(group.groupId, result, now));
            }
        }
        return result;
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
        releaseRelaySlot(entry);
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
        if (!tryReserve(reservedRelays, 1, maxTotalRelays)) {
            log.warn("bind rpc udp relay group {} rejected: global relay quota reached ({})", group.groupId, maxTotalRelays);
            return null;
        }
        boolean slotOwnedByEntry = false;
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
                    group.wire = RssDatagramHandler.server(group.dataPlaneToken, sender -> {
                        ConcurrentMap<InetSocketAddress, SocksContext> routes = ch.attr(SocksUdpRelayHandler.ATTR_CTX_MAP).get();
                        return routes != null && routes.containsKey(sender);
                    }, group.features, group.clientSalt, group.authSecret, group.udpMtu, group.udpFragEnabled, group.fixed);
                    if (group.fixed) {
                        final long prefix = UUID.fromString(group.dataPlaneToken).getMostSignificantBits();
                        fixedEntry.register(prefix, group.wire);
                        ch.closeFuture().addListener(f -> fixedEntry.remove(prefix, group.wire));
                    }
                    p.addLast(group.wire);
                } else if (config.getUdpReadTimeoutSeconds() > 0 || config.getUdpWriteTimeoutSeconds() > 0) {
                    p.addLast(new ProxyChannelIdleHandler(config.getUdpReadTimeoutSeconds(), config.getUdpWriteTimeoutSeconds()));
                }
                p.addLast(SocksUdpRelayHandler.DEFAULT);
            }).attr(SocksContext.SOCKS_SVR, server);
            if (group.fixed) {
                bootstrap = bootstrap.clone(fixedChannel.eventLoop());
            } else if (alias) {
                bootstrap = bootstrap.clone(primary.eventLoop());
            }
            ChannelFuture future = group.daily && !group.fixed ? bindDaily(bootstrap, (InetSocketAddress) udpBindAddr, config)
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
            slotOwnedByEntry = true;
            relay.attr(ATTR_RELAY_GROUP_ENTRY).set(entry);
            group.relays.put(udpBindLocalAddr.getPort(), entry);
            relays.put(udpBindLocalAddr.getPort(), entry);
            server.registerUdpRelay(relay);
            relay.closeFuture().addListener((ChannelFutureListener) f -> {
                releaseRelaySlot(entry);
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
        } finally {
            if (!slotOwnedByEntry) {
                reservedRelays.decrementAndGet();
            }
        }
    }

    private ChannelFuture bindDaily(Bootstrap bootstrap, InetSocketAddress bind, SocksConfig config) throws BindException {
        bootstrap.option(ChannelOption.SO_REUSEADDR, false);
        int first = config.getDailyPortFirst();
        if (first == 0) {
            return bootstrap.bind(bind);
        }
        first += config.isUdpHop() ? 2 : 1; // First slot(s) serve adaptive TCP streams.
        int stride = config.isUdpHop() && config.isRedirectUdpHops() ? 2 : 1;
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

    // Preserve socket budgets without installing legacy frame decoders or capping target Internet datagrams on the shared relay socket.
    private static SocketConfig dailySocketConfig(SocksConfig source) {
        if (!UdpRedundantSupport.isConfigured(source) && !source.isUdpCompressEnabled()
                && source.getUdpResilience() == null && source.getUdpMtu() == 0) {
            return source;
        }
        SocksConfig config = new SocksConfig();
        config.setTcpAsyncDnsMode(source.getTcpAsyncDnsMode());
        config.setDebug(source.isDebug());
        config.setReactorName(source.getReactorName());
        config.setOptimalSettings(source.getOptimalSettings());
        config.setUdpMtu(0);
        config.setDailyUdpMtu(source.resolveDailyUdpMtu());
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
        pruneIdempotentOpens(now);
    }

    private void closeGroup(UdpRelayGroup group, String reason) {
        if (group == null || !group.closedFlag.compareAndSet(false, true)) {
            return;
        }
        group.closed = true;
        groups.remove(group.groupId, group);
        releaseGroupSlot(group);
        for (UdpRelayEntry entry : group.relays.values()) {
            relays.remove(entry.relayPort, entry);
            releaseRelaySlot(entry);
            Sockets.closeOnFlushed(entry.udpChannel);
        }
        group.relays.clear();
        DiagnosticMetrics.record("socks.udp.relay.group.close.count", 1D,
                "result=" + ("timeout".equals(reason) ? "timeout" : "success") + ",reason=" + reason);
        recordState("close");
    }

    private void releaseGroupSlot(UdpRelayGroup group) {
        if (group.slotReleased.compareAndSet(false, true)) {
            reservedGroups.updateAndGet(v -> v > 0 ? v - 1 : 0);
            releaseClientSlot(group.clientKey);
        }
    }

    private void releaseRelaySlot(UdpRelayEntry entry) {
        if (entry != null && entry.slotReleased.compareAndSet(false, true)) {
            reservedRelays.updateAndGet(v -> v > 0 ? v - 1 : 0);
        }
    }

    private static boolean tryReserve(AtomicInteger counter, int delta, int limit) {
        while (true) {
            int cur = counter.get();
            if (cur + delta > limit) {
                return false;
            }
            if (counter.compareAndSet(cur, cur + delta)) {
                return true;
            }
        }
    }

    private boolean tryReserveClient(String clientKey, int limit) {
        while (true) {
            AtomicInteger count = clientGroupCounts.computeIfAbsent(clientKey, k -> new AtomicInteger());
            int cur = count.get();
            if (cur >= limit) {
                if (cur == 0) {
                    clientGroupCounts.remove(clientKey, count);
                }
                return false;
            }
            if (count.compareAndSet(cur, cur + 1)) {
                // Re-verify map entry wasn't concurrently evicted while transitioning 0 -> 1.
                if (clientGroupCounts.get(clientKey) == count) {
                    return true;
                }
                count.decrementAndGet();
            }
        }
    }

    private void releaseClientSlot(String clientKey) {
        AtomicInteger count = clientGroupCounts.get(clientKey);
        if (count == null) {
            return;
        }
        int after = count.updateAndGet(v -> v > 0 ? v - 1 : 0);
        if (after == 0) {
            clientGroupCounts.remove(clientKey, count);
        }
    }

    private void pruneIdempotentOpens(long now) {
        for (java.util.Map.Entry<String, IdempotentOpen> e : idempotentOpens.entrySet()) {
            if (now - e.getValue().createdAtMillis > IDEMPOTENT_TTL_MILLIS || !groups.containsKey(e.getValue().groupId)) {
                idempotentOpens.remove(e.getKey(), e.getValue());
            }
        }
    }

    private static String normalizeClientKey(String clientId) {
        return clientId == null || clientId.trim().isEmpty() ? "_default" : clientId.trim();
    }

    private void recordState(String action) {
        DiagnosticMetrics.record("socks.udp.relay.group.active.count", groups.size(), "action=" + action);
        DiagnosticMetrics.record("socks.udp.relay.group.relay.count", relays.size(), "action=" + action);
    }

    static boolean encrypted(Channel relay) {
        UdpRelayEntry entry = relay.attr(ATTR_RELAY_GROUP_ENTRY).get();
        return entry != null && (entry.group.features & RssDatagramHandler.AES) != 0;
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
        if (fixedChannel != null) fixedChannel.close();
        for (UdpRelayGroup group : groups.values()) {
            closeGroup(group, "dispose");
        }
        groups.clear();
        relays.clear();
        clientGroupCounts.clear();
        idempotentOpens.clear();
    }

    private static final class IdempotentOpen {
        final String groupId;
        final UdpRelayGroupOpenResult result;
        final long createdAtMillis;

        IdempotentOpen(String groupId, UdpRelayGroupOpenResult result, long createdAtMillis) {
            this.groupId = groupId;
            this.result = result;
            this.createdAtMillis = createdAtMillis;
        }
    }

    static final class UdpRelayGroup {
        final String groupId;
        // Negotiated daily frames carry this per-group cookie. RPC authorization is independent.
        final String dataPlaneToken;
        final String clientKey;
        final boolean daily, fixed;
        final int features;
        final UUID clientSalt;
        final byte[] authSecret;
        final boolean udpFragEnabled;
        final int udpMtu;
        volatile Channel primary;
        volatile RssDatagramHandler wire;
        final InetSocketAddress clientAddr;
        final InetSocketAddress firstDestination;
        final int maxRelayCount;
        final long idleTimeoutMillis;
        final long createdAtMillis;
        final ConcurrentMap<Integer, UdpRelayEntry> relays = new ConcurrentHashMap<>();
        final java.util.concurrent.atomic.AtomicBoolean closedFlag = new java.util.concurrent.atomic.AtomicBoolean();
        final java.util.concurrent.atomic.AtomicBoolean slotReleased = new java.util.concurrent.atomic.AtomicBoolean();
        volatile long lastActiveAtMillis;
        volatile boolean closed;

        UdpRelayGroup(String groupId, String dataPlaneToken, String clientKey, InetSocketAddress clientAddr,
                InetSocketAddress firstDestination, int maxRelayCount, long idleTimeoutMillis, long now,
                int features, UUID clientSalt, boolean udpFragEnabled, int udpMtu, boolean fixed) {
            this.clientKey = clientKey;
            this.fixed = fixed;
            this.features = features;
            this.clientSalt = clientSalt;
            this.udpFragEnabled = udpFragEnabled;
            this.udpMtu = udpMtu;
            if ((fixed || (features & PROFILES) != 0 || udpFragEnabled) && (features & RssDatagramHandler.AES) == 0) {
                authSecret = new byte[32];
                RANDOM.nextBytes(authSecret);
            } else {
                authSecret = null;
            }
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
        final java.util.concurrent.atomic.AtomicBoolean slotReleased = new java.util.concurrent.atomic.AtomicBoolean();
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
