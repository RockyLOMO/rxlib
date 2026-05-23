package org.rx.net.socks.upstream;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.CachePolicy;
import org.rx.core.Tasks;
import org.rx.core.cache.MemoryCache;
import org.rx.diagnostic.DiagnosticMetrics;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.Sockets;
import org.rx.net.socks.Socks5Client;
import org.rx.net.socks.Socks5Client.Socks5UdpLease;
import org.rx.net.socks.Socks5Client.Socks5UdpSession;
import org.rx.net.socks.SocksUdpRelayHandler;
import org.rx.net.socks.Socks5UpstreamPoolManager;
import org.rx.net.socks.SocksConfig;
import org.rx.net.socks.SocksRpcCapabilities;
import org.rx.net.socks.SocksRpcContract;
import org.rx.net.socks.UdpRelayControlMode;
import org.rx.net.socks.UdpRelayEndpoint;
import org.rx.net.socks.UdpRelayGroupOpenRequest;
import org.rx.net.socks.UdpRelayGroupOpenResult;
import org.rx.net.socks.UdpRelayGroupUpdateResult;
import org.rx.net.socks.UdpRelayAttributes;
import org.rx.net.socks.UdpRedundantSupport;
import org.rx.net.socks.UdpLeasePoolKey;
import org.rx.net.udp.UdpPortHoppingConfig;
import org.rx.net.udp.UdpPortHoppingMode;
import java.net.InetSocketAddress;
import org.rx.net.support.UpstreamSupport;

import java.nio.channels.ClosedChannelException;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.rx.core.Extends.tryClose;

@Slf4j
public class SocksUdpUpstream extends Upstream {
    private static final AttributeKey<SessionGroup> ATTR_UDP_SESSION =
            AttributeKey.valueOf("socksUdpUpstreamSessionGroup");
    private static final AttributeKey<CompletableFuture<SessionGroup>> ATTR_UDP_SESSION_INIT =
            AttributeKey.valueOf("socksUdpUpstreamSessionInit");
    private static final InetSocketAddress[] EMPTY_RELAY_ADDRESSES = new InetSocketAddress[0];
    private static final MemoryCache<UdpLeasePoolKey, Boolean> RPC_RELAY_GROUP_BREAKER = new MemoryCache<>();
    private static final ConcurrentMap<UdpLeasePoolKey, AtomicInteger> RPC_RELAY_GROUP_FAILURES = new ConcurrentHashMap<>();

    private final UpstreamSupport next;

    public SocksUdpUpstream(InetSocketAddress dstEp, @NonNull SocksConfig config, @NonNull UpstreamSupport next) {
        super(dstEp, config);
        this.next = next;
    }

    public AuthenticEndpoint getServerEndpoint() {
        return next.getEndpoint();
    }

    public UdpLeasePoolKey poolKey() {
        return UdpLeasePoolKey.from(next.getEndpoint(), config, config.getReactorName());
    }

    public long resolveRelayIdleHintMillis() {
        Map<String, String> parameters = next.getEndpoint().getParameters();
        if (parameters == null) {
            return 0L;
        }
        String value = parameters.get("udpRelayIdleSeconds");
        if (value == null) {
            return 0L;
        }
        try {
            long seconds = Long.parseLong(value);
            return seconds <= 0 ? 0L : Math.max(0L, seconds * 1000L - 5000L);
        } catch (NumberFormatException e) {
            log.warn("invalid udpRelayIdleSeconds {} for {}", value, next.getEndpoint());
            return 0L;
        }
    }

    public InetSocketAddress getUdpRelayAddress(Channel channel) {
        SessionGroup group = activeGroup(channel);
        return group != null ? group.primaryRelayAddress() : null;
    }

    public InetSocketAddress selectUdpRelayAddress(Channel channel) {
        return selectUdpRelayAddressAndRecord(channel, 0);
    }

    public InetSocketAddress selectUdpRelayAddressAndRecord(Channel channel, int bytes) {
        SessionGroup group = activeGroup(channel);
        if (group == null) {
            return null;
        }
        InetSocketAddress relayAddress = group.selectRelayAddress();
        if (bytes > 0) {
            group.recordBytes(bytes);
        }
        maintainGroup(channel, group);
        return relayAddress;
    }

    public boolean ownsUdpRelayAddress(Channel channel, InetSocketAddress sender) {
        SessionGroup group = activeGroup(channel);
        return group != null && group.containsRelayAddress(sender);
    }

    public InetSocketAddress[] snapshotUdpRelayAddresses(Channel channel) {
        SessionGroup group = activeGroup(channel);
        return group != null ? group.snapshotRelayAddresses() : EMPTY_RELAY_ADDRESSES;
    }

    public void recordUdpTraffic(Channel channel, int bytes) {
        if (bytes <= 0) {
            return;
        }
        SessionGroup group = activeGroup(channel);
        if (group == null) {
            return;
        }
        group.recordBytes(bytes);
        maintainGroup(channel, group);
    }

    private void maintainGroup(Channel channel, SessionGroup group) {
        maybeReplenishGroup(channel, group);
        maybeExpandGroup(channel, group);
        maybeHeartbeatGroup(channel, group);
    }

    private SessionGroup activeGroup(Channel channel) {
        SessionGroup group = channel.attr(ATTR_UDP_SESSION).get();
        if (group == null) {
            return null;
        }
        if (group.isValid()) {
            return group;
        }
        invalidateGroup(channel, group, false, "stale-session");
        return null;
    }

    @Override
    public void initChannel(Channel channel) {
        initChannelAsync(channel);
    }

    @Override
    public CompletableFuture<Void> initChannelAsync(Channel channel) {
        SessionGroup group = channel.attr(ATTR_UDP_SESSION).get();
        if (group != null && group.isValid()) {
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<SessionGroup> initFuture = channel.attr(ATTR_UDP_SESSION_INIT).get();
        if (initFuture != null) {
            return initFuture.thenApply(v -> null);
        }

        CompletableFuture<SessionGroup> created = new CompletableFuture<>();
        CompletableFuture<SessionGroup> existing = channel.attr(ATTR_UDP_SESSION_INIT).setIfAbsent(created);
        if (existing != null) {
            return existing.thenApply(v -> null);
        }

        Tasks.runAsync(() -> initializeSessionOffLoop(channel, created));
        return created.thenApply(v -> null);
    }

    private boolean claimRelay(Channel channel, Socks5UdpLease lease, SocksRpcContract facade,
                               Socks5UpstreamPoolManager poolManager, SocksConfig socksConfig) {
        try {
            InetSocketAddress clientAddr = resolveClaimClientAddress(channel, lease);
            boolean ok = facade.claimUdpRelay(lease.getRelayPort(), clientAddr, SocksRpcContract.rpcToken());
            if (ok) {
                poolManager.onUdpRpcSuccess(poolKey());
                DiagnosticMetrics.record("socks.udp.lease.pool.claim.count", 1D,
                        "result=success");
                return true;
            }
            DiagnosticMetrics.record("socks.udp.lease.pool.claim.count", 1D,
                    "result=fail");
            poolManager.onUdpRpcFailure(poolKey(), socksConfig, "claim", null);
        } catch (Exception e) {
            DiagnosticMetrics.record("socks.udp.lease.pool.claim.count", 1D,
                    "result=error");
            poolManager.onUdpRpcFailure(poolKey(), socksConfig, "claim", e);
        }
        return false;
    }

    static InetSocketAddress resolveClaimClientAddress(Channel channel, Socks5UdpLease lease) {
        InetSocketAddress udpLocalAddr = channel.localAddress() instanceof InetSocketAddress
                ? (InetSocketAddress) channel.localAddress() : null;
        if (udpLocalAddr == null) {
            return null;
        }

        if (udpLocalAddr.getAddress() != null && !udpLocalAddr.getAddress().isAnyLocalAddress()) {
            return udpLocalAddr;
        }

        InetSocketAddress tcpLocalAddr = lease != null && lease.getTcpControl() != null
                && lease.getTcpControl().localAddress() instanceof InetSocketAddress
                ? (InetSocketAddress) lease.getTcpControl().localAddress() : null;
        if (tcpLocalAddr != null && tcpLocalAddr.getAddress() != null
                && !tcpLocalAddr.getAddress().isAnyLocalAddress()) {
            return new InetSocketAddress(tcpLocalAddr.getAddress(), udpLocalAddr.getPort());
        }
        return null;
    }

    private SessionHolder acquireHolder(Channel channel) {
        SocksConfig socksConfig = (SocksConfig) config;
        SocksRpcContract facade = next.getFacade();
        Socks5UpstreamPoolManager poolManager = Socks5UpstreamPoolManager.INSTANCE;
        if (socksConfig.isUdpLeasePoolEnabled() && facade != null && !poolManager.isUdpBreakerOpen(poolKey())) {
            Socks5UpstreamPoolManager.UdpLeasePool pool = poolManager.udpPool(this);
            if (pool != null) {
                Socks5UdpLease lease = pool.borrow();
                if (lease != null) {
                    if (claimRelay(channel, lease, facade, poolManager, socksConfig)) {
                        return SessionHolder.pooled(pool, lease);
                    }
                    pool.discard(lease);
                }
            }
        }

        return initSlowPath(channel);
    }

    private SessionGroup acquireGroup(Channel channel) {
        SessionGroup group = channel.attr(ATTR_UDP_SESSION).get();
        if (group != null && group.isValid()) {
            return group;
        }
        if (group != null) {
            channel.attr(ATTR_UDP_SESSION).set(null);
        }

        SocksConfig socksConfig = (SocksConfig) config;
        int hopCount = resolveInitialHopCount(socksConfig);
        SessionGroup rpcGroup = acquireRpcRelayGroup(channel, socksConfig, hopCount);
        if (rpcGroup != null) {
            return rpcGroup;
        }
        if (hopCount <= 1) {
            return newSessionGroup(new SessionHolder[]{acquireHolder(channel)}, socksConfig, 1);
        }

        int minActive = Math.max(1, Math.min(hopCount, socksConfig.getUdpPortHoppingMinActiveHops()));
        SessionHolder[] holders = new SessionHolder[hopCount];
        int count = 0;
        Throwable lastError = null;
        for (int i = 0; i < hopCount; i++) {
            SessionHolder holder = null;
            try {
                holder = acquireHolder(channel);
                if (holder != null && holder.isValid() && !containsRelayAddress(holders, count, holder.relayAddr)) {
                    holders[count++] = holder;
                } else {
                    closeHolder(holder);
                }
            } catch (Throwable e) {
                lastError = e;
                closeHolder(holder);
                DiagnosticMetrics.record("socks.udp.porthop.acquire.count", 1D,
                        "result=fail,index=" + i + ",requested=" + hopCount);
            }
        }

        if (count < minActive) {
            for (int i = 0; i < count; i++) {
                closeHolder(holders[i]);
            }
            throw new IllegalStateException("UDP upstream port hopping acquire failed, active=" + count
                    + ", minActive=" + minActive, lastError);
        }
        if (count < hopCount) {
            DiagnosticMetrics.record("socks.udp.porthop.acquire.count", 1D,
                    "result=partial,active=" + count + ",requested=" + hopCount);
        } else {
            DiagnosticMetrics.record("socks.udp.porthop.acquire.count", 1D,
                    "result=success,active=" + count + ",requested=" + hopCount);
        }
        return newSessionGroup(Arrays.copyOf(holders, count), socksConfig, hopCount);
    }

    private SessionGroup acquireRpcRelayGroup(Channel channel, SocksConfig socksConfig, int hopCount) {
        UdpRelayControlMode mode = socksConfig.getUdpRelayControlMode();
        if (mode == UdpRelayControlMode.SOCKS5_COMPAT || mode == UdpRelayControlMode.RX_SOCKS5_BATCH) {
            return null;
        }
        if (mode == UdpRelayControlMode.AUTO && !socksConfig.isUdpPortHoppingEnabled()) {
            return null;
        }
        UdpLeasePoolKey key = poolKey();
        if (mode == UdpRelayControlMode.AUTO && RPC_RELAY_GROUP_BREAKER.containsKey(key)) {
            DiagnosticMetrics.record("socks.udp.relay.control.mode.count", 1D,
                    "mode=socks5_compat,reason=breaker");
            return null;
        }

        SocksRpcContract facade = next.getFacade();
        if (facade == null) {
            return handleRpcRelayGroupUnavailable(socksConfig, "facade-null", null);
        }

        try {
            SocksRpcCapabilities capabilities = facade.capabilities(SocksRpcContract.rpcToken());
            if (capabilities == null || !capabilities.has(SocksRpcCapabilities.UDP_RELAY_GROUP)) {
                return handleRpcRelayGroupUnavailable(socksConfig, "unsupported", null);
            }

            int initialCount = Math.max(1, hopCount);
            int minActive = Math.max(1, Math.min(initialCount, socksConfig.getUdpPortHoppingMinActiveHops()));
            int maxRelayCount = socksConfig.isUdpPortHoppingAdaptive()
                    ? Math.max(initialCount, socksConfig.getUdpPortHoppingMaxHopCount())
                    : initialCount;
            maxRelayCount = Math.min(clampHopCount(maxRelayCount), Math.max(1, socksConfig.getUdpRelayControlMaxRelaysPerGroup()));
            if (capabilities.getMaxRelaysPerGroup() > 0) {
                maxRelayCount = Math.min(maxRelayCount, capabilities.getMaxRelaysPerGroup());
            }
            initialCount = Math.min(initialCount, maxRelayCount);
            minActive = Math.min(minActive, initialCount);

            UdpRelayGroupOpenRequest request = new UdpRelayGroupOpenRequest();
            request.setClientId(key.toString());
            request.setClientAddr(resolveRpcClientAddress(channel));
            request.setFirstDestination(destination);
            request.setInitialRelayCount(initialCount);
            request.setMinActiveRelays(minActive);
            request.setMaxRelayCount(maxRelayCount);
            request.setIdleTimeoutMillis(socksConfig.getUdpRelayGroupIdleMillis());

            UdpRelayGroupOpenResult result = facade.openUdpRelayGroup(request, SocksRpcContract.rpcToken());
            if (result == null || !result.isSupported() || !result.isSuccess()) {
                String reason = result == null ? "null-result"
                        : !result.isSupported() ? "unsupported"
                        : result.getErrorCode();
                return handleRpcRelayGroupUnavailable(socksConfig, reason, null);
            }

            String controlToken = result.getToken() != null ? result.getToken() : SocksRpcContract.rpcToken();
            RpcRelayGroupControl control = new RpcRelayGroupControl(facade, result.getGroupId(), controlToken);
            SessionHolder[] holders = toRpcHolders(control, result.getRelays());
            if (holders.length < minActive) {
                control.closeGroup();
                return handleRpcRelayGroupUnavailable(socksConfig, "min-active", null);
            }
            onRpcRelayGroupSuccess(key);
            DiagnosticMetrics.record("socks.udp.relay.control.mode.count", 1D,
                    "mode=rss_rpc");
            return newSessionGroup(holders, socksConfig, initialCount, control);
        } catch (Throwable e) {
            onRpcRelayGroupFailure(key, socksConfig, "open", e);
            return handleRpcRelayGroupUnavailable(socksConfig, "error", e);
        }
    }

    private SessionGroup handleRpcRelayGroupUnavailable(SocksConfig socksConfig, String reason, Throwable error) {
        UdpRelayControlMode mode = socksConfig.getUdpRelayControlMode();
        if ((mode == UdpRelayControlMode.RSS_RPC || mode == UdpRelayControlMode.AUTO)
                && !socksConfig.isUdpRelayControlFallbackToSocks5()) {
            throw new IllegalStateException("RX UDP relay group unavailable: " + reason, error);
        }
        if (mode == UdpRelayControlMode.RSS_RPC || mode == UdpRelayControlMode.AUTO) {
            DiagnosticMetrics.record("socks.udp.relay.control.mode.count", 1D,
                    "mode=fallback,reason=" + reason);
            if (error != null) {
                log.warn("RX UDP relay group unavailable for {}, fallback={}",
                        next.getEndpoint(), socksConfig.isUdpRelayControlFallbackToSocks5(), error);
            }
        }
        return null;
    }

    private SessionHolder[] toRpcHolders(RpcRelayGroupControl control, java.util.List<UdpRelayEndpoint> endpoints) {
        if (endpoints == null || endpoints.isEmpty()) {
            return new SessionHolder[0];
        }
        SessionHolder[] holders = new SessionHolder[endpoints.size()];
        int count = 0;
        for (UdpRelayEndpoint endpoint : endpoints) {
            if (endpoint == null) {
                continue;
            }
            InetSocketAddress relayAddress = normalizeRpcRelayAddress(endpoint.getRelayAddress());
            if (relayAddress != null && !containsRelayAddress(holders, count, relayAddress)) {
                holders[count++] = SessionHolder.rpc(control, endpoint.getRelayId(), relayAddress);
            }
        }
        return count == holders.length ? holders : Arrays.copyOf(holders, count);
    }

    private InetSocketAddress normalizeRpcRelayAddress(InetSocketAddress relayAddress) {
        if (relayAddress == null) {
            return null;
        }
        if (relayAddress.getAddress() == null || relayAddress.getAddress().isAnyLocalAddress()) {
            InetSocketAddress serverAddress = next.getEndpoint().getInetEndpoint();
            if (serverAddress != null) {
                if (serverAddress.getAddress() != null) {
                    return new InetSocketAddress(serverAddress.getAddress(), relayAddress.getPort());
                }
                return Sockets.newUnresolvedEndpoint(serverAddress.getHostString(), relayAddress.getPort());
            }
        }
        return relayAddress;
    }

    private InetSocketAddress resolveRpcClientAddress(Channel channel) {
        InetSocketAddress udpLocalAddr = channel.localAddress() instanceof InetSocketAddress
                ? (InetSocketAddress) channel.localAddress() : null;
        if (udpLocalAddr == null) {
            return null;
        }
        if (udpLocalAddr.getAddress() != null && !udpLocalAddr.getAddress().isAnyLocalAddress()) {
            return udpLocalAddr;
        }
        // any-local 只说明本地 UDP socket 尚未暴露实际出口地址，不能用上游 server IP 猜 clientAddr。
        // 让服务端 relay 以首个真实 UDP sender 建立 client lock，避免公网部署下锁到错误地址。
        return null;
    }

    private void onRpcRelayGroupSuccess(UdpLeasePoolKey key) {
        RPC_RELAY_GROUP_FAILURES.remove(key);
        RPC_RELAY_GROUP_BREAKER.remove(key);
    }

    private void onRpcRelayGroupFailure(UdpLeasePoolKey key, SocksConfig socksConfig, String phase, Throwable cause) {
        AtomicInteger failures = RPC_RELAY_GROUP_FAILURES.computeIfAbsent(key, k -> new AtomicInteger());
        int count = failures.incrementAndGet();
        DiagnosticMetrics.record("socks.udp.relay.group.open.count", 1D,
                "result=fail,phase=" + phase);
        if (count < socksConfig.getUdpRelayControlFailureThreshold()) {
            if (cause != null) {
                log.warn("RX UDP relay group {} fail {}", phase, key, cause);
            }
            return;
        }
        failures.set(0);
        int seconds = (int) Math.max(1L, (socksConfig.getUdpRelayControlBreakerOpenMillis() + 999L) / 1000L);
        RPC_RELAY_GROUP_BREAKER.put(key, Boolean.TRUE, CachePolicy.absolute(seconds));
        DiagnosticMetrics.record("socks.udp.relay.control.breaker.count", 1D,
                "action=open");
        if (cause != null) {
            log.warn("RX UDP relay group breaker open after {} failures {}", count, key, cause);
        }
    }

    private static int resolveInitialHopCount(SocksConfig socksConfig) {
        if (!socksConfig.isUdpPortHoppingEnabled()) {
            return 1;
        }
        int hopCount = socksConfig.isUdpPortHoppingAdaptive()
                ? socksConfig.getUdpPortHoppingMinHopCount()
                : socksConfig.getUdpPortHoppingHopCount();
        return clampHopCount(hopCount);
    }

    private static SessionGroup newSessionGroup(SessionHolder[] holders, SocksConfig socksConfig, int targetHopCount) {
        return newSessionGroup(holders, socksConfig, targetHopCount, null);
    }

    private static SessionGroup newSessionGroup(SessionHolder[] holders, SocksConfig socksConfig,
            int targetHopCount, RpcRelayGroupControl control) {
        if (!socksConfig.isUdpPortHoppingEnabled()) {
            return new SessionGroup(holders, UdpPortHoppingMode.ROUND_ROBIN, targetHopCount, control);
        }
        return new SessionGroup(holders, socksConfig.getUdpPortHoppingMode(),
                socksConfig.isUdpPortHoppingAdaptive(),
                socksConfig.getUdpPortHoppingAdaptiveScaleUpBytes(),
                socksConfig.getUdpPortHoppingAdaptiveScaleUpActiveMillis(),
                socksConfig.getUdpPortHoppingAdaptiveScaleUpCooldownMillis(),
                targetHopCount,
                control);
    }

    private static int clampHopCount(int hopCount) {
        return Math.max(1, Math.min(UdpPortHoppingConfig.MAX_HOP_COUNT, hopCount));
    }

    private static boolean containsRelayAddress(SessionHolder[] holders, int count, InetSocketAddress relayAddr) {
        for (int i = 0; i < count; i++) {
            if (sameAddress(holders[i].relayAddr, relayAddr)) {
                return true;
            }
        }
        return false;
    }

    private void initializeSessionOffLoop(Channel channel, CompletableFuture<SessionGroup> future) {
        SessionGroup group = null;
        Throwable error = null;
        try {
            group = acquireGroup(channel);
        } catch (Throwable e) {
            error = e;
        }

        final SessionGroup finalGroup = group;
        final Throwable finalError = error;
        channel.eventLoop().execute(() -> completeSessionInit(channel, future, finalGroup, finalError));
    }

    private SessionHolder initSlowPath(Channel channel) {
        AuthenticEndpoint svrEp = next.getEndpoint();
        Socks5Client client = new Socks5Client(svrEp, config);
        try {
            long timeout = config != null && config.getConnectTimeoutMillis() > 0
                    ? config.getConnectTimeoutMillis() : 10000L;
            Socks5UdpSession session = client.udpAssociateAsync(channel).get(timeout, TimeUnit.MILLISECONDS);
            return SessionHolder.session(client, session);
        } catch (Exception e) {
            tryClose(client);
            throw new IllegalStateException("Failed to establish UDP upstream session with " + svrEp, e);
        }
    }

    private void completeSessionInit(Channel channel, CompletableFuture<SessionGroup> future,
                                     SessionGroup group, Throwable error) {
        CompletableFuture<SessionGroup> activeInit = channel.attr(ATTR_UDP_SESSION_INIT).get();
        if (activeInit != future) {
            closeGroup(group, false);
            return;
        }
        channel.attr(ATTR_UDP_SESSION_INIT).set(null);

        SessionGroup activeGroup = channel.attr(ATTR_UDP_SESSION).get();
        if (activeGroup != null && activeGroup.isValid()) {
            closeGroup(group, false);
            future.complete(activeGroup);
            return;
        }

        if (error != null) {
            log.error("Failed to establish UDP upstream session with {}", next.getEndpoint(), error);
            future.completeExceptionally(error);
            return;
        }
        if (group == null || !group.isValid()) {
            future.completeExceptionally(new IllegalStateException("UDP upstream session group is null or invalid"));
            return;
        }
        if (!channel.isActive()) {
            closeGroup(group, false);
            future.completeExceptionally(new ClosedChannelException());
            return;
        }
        bindGroup(channel, group);
        future.complete(group);
    }

    private void closeHolder(SessionHolder holder) {
        if (holder == null) {
            return;
        }
        if (holder.rpcControl != null) {
            holder.rpcControl.removeRelay(holder.relayAddr != null ? holder.relayAddr.getPort() : 0);
            return;
        }
        if (holder.pooled) {
            if (holder.pool != null) {
                holder.pool.discard(holder.lease);
            } else {
                tryClose(holder.lease);
            }
            return;
        }
        tryClose(holder.session);
        tryClose(holder.client);
    }

    private void closeGroup(SessionGroup group, boolean resetPooledLease) {
        if (group == null) {
            return;
        }
        if (group.rpcControl != null) {
            group.rpcControl.closeGroup();
            return;
        }
        SessionHolder[] holders = group.holders;
        for (SessionHolder holder : holders) {
            if (resetPooledLease) {
                closeAfterRelayClose(holder);
            } else {
                closeHolder(holder);
            }
        }
    }

    private void bindGroup(Channel channel, SessionGroup group) {
        channel.attr(ATTR_UDP_SESSION).set(group);
        group.retain(next);
        InetSocketAddress[] relayAddresses = group.snapshotRelayAddresses();
        for (InetSocketAddress relayAddr : relayAddresses) {
            addRequestRedundantPeer(channel, relayAddr);
        }
        recordGroupActiveCount(channel, group, "bind");
        final SessionGroup finalGroup = group;
        SessionHolder[] holders = group.holders;
        for (SessionHolder holder : holders) {
            registerControlCloseListener(channel, finalGroup, holder);
        }
        channel.closeFuture().addListener(f -> {
            invalidateGroup(channel, finalGroup, true, "relay-close");
        });
    }

    private void registerControlCloseListener(Channel channel, SessionGroup group, SessionHolder holder) {
        if (holder == null) {
            return;
        }
        Channel controlChannel = holder.controlChannel();
        if (controlChannel != null) {
            controlChannel.closeFuture().addListener(f ->
                    channel.eventLoop().execute(() -> handleHolderClosed(channel, group, holder)));
        }
    }

    private void handleHolderClosed(Channel channel, SessionGroup group, SessionHolder holder) {
        SessionGroup active = channel.attr(ATTR_UDP_SESSION).get();
        if (active != group || holder == null || !group.removeHolder(holder)) {
            return;
        }

        UdpRelayAttributes.removeRedundantPeer(channel, holder.relayAddr);
        SocksUdpRelayHandler.onUpstreamRelayRemoved(channel, holder.relayAddr, this);
        closeHolder(holder);
        recordGroupActiveCount(channel, group, "hop-remove");

        SocksConfig socksConfig = (SocksConfig) config;
        int minActive = Math.max(1, socksConfig.getUdpPortHoppingMinActiveHops());
        if (group.shouldInvalidate(minActive)) {
            invalidateGroup(channel, group, false, "control-close-min-active");
            return;
        }
        maybeReplenishGroup(channel, group);
    }

    private void maybeExpandGroup(Channel channel, SessionGroup group) {
        SocksConfig socksConfig = (SocksConfig) config;
        if (!socksConfig.isUdpPortHoppingAdaptive()) {
            return;
        }
        if (group.needsReplenish()) {
            return;
        }
        int maxHopCount = clampHopCount(socksConfig.getUdpPortHoppingMaxHopCount());
        if (group.holderCount() >= maxHopCount || !group.tryBeginScaleUp(System.currentTimeMillis())) {
            return;
        }
        Tasks.runAsync(() -> expandGroupOffLoop(channel, group, maxHopCount));
    }

    private void maybeReplenishGroup(Channel channel, SessionGroup group) {
        if (!group.needsReplenish()) {
            return;
        }
        SocksConfig socksConfig = (SocksConfig) config;
        if (!group.tryBeginReplenish(System.currentTimeMillis(), socksConfig.getUdpPortHoppingReplenishDelayMillis())) {
            return;
        }
        Tasks.runAsync(() -> replenishGroupOffLoop(channel, group));
    }

    private void maybeHeartbeatGroup(Channel channel, SessionGroup group) {
        if (group.rpcControl == null) {
            return;
        }
        SocksConfig socksConfig = (SocksConfig) config;
        long intervalMillis = socksConfig.getUdpRelayGroupHeartbeatMillis();
        int failureThreshold = socksConfig.getUdpRelayControlFailureThreshold();
        if (intervalMillis <= 0L || !group.tryBeginHeartbeat(System.currentTimeMillis(), intervalMillis)) {
            return;
        }
        Tasks.runAsync(() -> {
            boolean ok = group.rpcControl.heartbeat();
            channel.eventLoop().execute(() -> {
                if (group.finishHeartbeat(ok, failureThreshold)) {
                    invalidateGroup(channel, group, false, "heartbeat-fail");
                }
            });
        });
    }

    private void replenishGroupOffLoop(Channel channel, SessionGroup group) {
        SessionHolder holder = null;
        Throwable error = null;
        try {
            if (group.needsReplenish()) {
                holder = acquireAdditionalHolder(channel, group);
            }
        } catch (Throwable e) {
            error = e;
        }

        final SessionHolder finalHolder = holder;
        final Throwable finalError = error;
        channel.eventLoop().execute(() -> completeGroupReplenish(channel, group, finalHolder, finalError));
    }

    private void completeGroupReplenish(Channel channel, SessionGroup group, SessionHolder holder, Throwable error) {
        boolean success = false;
        try {
            SessionGroup active = channel.attr(ATTR_UDP_SESSION).get();
            if (active != group || !channel.isActive() || !group.isValid() || !group.needsReplenish()) {
                closeHolder(holder);
                return;
            }
            if (error != null) {
                log.warn("UDP upstream port hopping replenish failed for {}", next.getEndpoint(), error);
                DiagnosticMetrics.record("socks.udp.porthop.replenish.count", 1D,
                        "result=fail,reason=acquire");
                return;
            }
            if (holder == null || !holder.isValid()) {
                closeHolder(holder);
                DiagnosticMetrics.record("socks.udp.porthop.replenish.count", 1D,
                        "result=fail,reason=invalid");
                return;
            }
            if (group.containsRelayAddress(holder.relayAddr)) {
                closeHolder(holder);
                DiagnosticMetrics.record("socks.udp.porthop.replenish.count", 1D,
                        "result=skip,reason=duplicate");
                return;
            }

            group.addHolder(holder);
            addRequestRedundantPeer(channel, holder.relayAddr);
            SocksUdpRelayHandler.onUpstreamRelayAdded(channel, holder.relayAddr, this);
            registerControlCloseListener(channel, group, holder);
            recordGroupActiveCount(channel, group, "replenish");
            DiagnosticMetrics.record("socks.udp.porthop.replenish.count", 1D,
                    "result=success");
            success = true;
        } finally {
            group.finishReplenish(success, System.currentTimeMillis());
        }
    }

    private void expandGroupOffLoop(Channel channel, SessionGroup group, int maxHopCount) {
        SessionHolder holder = null;
        Throwable error = null;
        try {
            if (group.holderCount() < maxHopCount) {
                holder = acquireAdditionalHolder(channel, group);
            }
        } catch (Throwable e) {
            error = e;
        }

        final SessionHolder finalHolder = holder;
        final Throwable finalError = error;
        channel.eventLoop().execute(() -> completeGroupExpand(channel, group, finalHolder, finalError, maxHopCount));
    }

    private SessionHolder acquireAdditionalHolder(Channel channel, SessionGroup group) {
        if (group != null && group.rpcControl != null) {
            return acquireRpcRelay(group.rpcControl);
        }
        return acquireHolder(channel);
    }

    private SessionHolder acquireRpcRelay(RpcRelayGroupControl control) {
        UdpRelayGroupUpdateResult result = control.addRelays(1);
        if (result == null || !result.isSupported() || !result.isSuccess()
                || result.getRelays() == null || result.getRelays().isEmpty()) {
            throw new IllegalStateException("RX UDP relay group add failed");
        }
        SessionHolder[] holders = toRpcHolders(control, result.getRelays());
        if (holders.length == 0) {
            throw new IllegalStateException("RX UDP relay group add returned no usable relay");
        }
        return holders[0];
    }

    private void completeGroupExpand(Channel channel, SessionGroup group, SessionHolder holder,
                                     Throwable error, int maxHopCount) {
        boolean success = false;
        try {
            SessionGroup active = channel.attr(ATTR_UDP_SESSION).get();
            if (active != group || !channel.isActive() || !group.isValid() || group.holderCount() >= maxHopCount) {
                closeHolder(holder);
                return;
            }
            if (error != null) {
                log.warn("UDP upstream port hopping adaptive expand failed for {}", next.getEndpoint(), error);
                DiagnosticMetrics.record("socks.udp.porthop.adaptive.scale.count", 1D,
                        "result=fail,reason=acquire");
                return;
            }
            if (holder == null || !holder.isValid()) {
                closeHolder(holder);
                DiagnosticMetrics.record("socks.udp.porthop.adaptive.scale.count", 1D,
                        "result=fail,reason=invalid");
                return;
            }
            if (group.containsRelayAddress(holder.relayAddr)) {
                closeHolder(holder);
                DiagnosticMetrics.record("socks.udp.porthop.adaptive.scale.count", 1D,
                        "result=skip,reason=duplicate");
                return;
            }

            group.addHolder(holder);
            addRequestRedundantPeer(channel, holder.relayAddr);
            SocksUdpRelayHandler.onUpstreamRelayAdded(channel, holder.relayAddr, this);
            registerControlCloseListener(channel, group, holder);
            recordGroupActiveCount(channel, group, "adaptive-scale-up");
            DiagnosticMetrics.record("socks.udp.porthop.adaptive.scale.count", 1D,
                    "result=success");
            success = true;
        } finally {
            group.finishScaleUp(success, System.currentTimeMillis());
        }
    }

    private void invalidateGroup(Channel relay, SessionGroup group, boolean resetPooledLease, String reason) {
        Runnable task = () -> {
            SessionGroup active = relay.attr(ATTR_UDP_SESSION).get();
            if (active != group) {
                return;
            }
            InetSocketAddress[] relayAddresses = active.snapshotAllRelayAddresses();
            relay.attr(ATTR_UDP_SESSION).set(null);
            active.releaseActive();
            SocksUdpRelayHandler.onUpstreamSessionInvalidated(relay, relayAddresses, this);
            DiagnosticMetrics.record("socks.udp.session.invalidate.count", 1D,
                    "reason=" + reason + ",pooled=" + active.hasPooledHolder());

            if (!resetPooledLease) {
                closeGroup(active, false);
                return;
            }
            closeGroup(active, true);
        };
        if (relay.eventLoop().inEventLoop()) {
            task.run();
        } else {
            relay.eventLoop().execute(task);
        }
    }

    private void recordGroupActiveCount(Channel channel, SessionGroup group, String action) {
        DiagnosticMetrics.record("socks.udp.porthop.group.active.count", group.activeCount(),
                "action=" + action);
    }

    private void closeAfterRelayClose(SessionHolder active) {
        if (active == null) {
            return;
        }
        if (active.rpcControl != null) {
            active.rpcControl.closeGroup();
            return;
        }
        if (!active.pooled) {
            tryClose(active.session);
            tryClose(active.client);
            return;
        }

        SocksRpcContract facade = next.getFacade();
        if (facade == null) {
            if (active.pool != null) {
                active.pool.discard(active.lease);
            } else {
                tryClose(active.lease);
            }
            return;
        }

        Tasks.runAsync(() -> {
            boolean ok = false;
            try {
                ok = facade.resetUdpRelay(active.lease.getRelayPort(), SocksRpcContract.rpcToken());
                if (ok) {
                    DiagnosticMetrics.record("socks.udp.lease.pool.reset.count", 1D,
                            "result=success");
                    Socks5UpstreamPoolManager.INSTANCE.onUdpRpcSuccess(poolKey());
                } else {
                    DiagnosticMetrics.record("socks.udp.lease.pool.reset.count", 1D,
                            "result=fail");
                    Socks5UpstreamPoolManager.INSTANCE.onUdpRpcFailure(poolKey(), (SocksConfig) config, "reset", null);
                }
            } catch (Throwable e) {
                DiagnosticMetrics.record("socks.udp.lease.pool.reset.count", 1D,
                        "result=error");
                Socks5UpstreamPoolManager.INSTANCE.onUdpRpcFailure(poolKey(), (SocksConfig) config, "reset", e);
            }

            Socks5UpstreamPoolManager.UdpLeasePool pool = active.pool;
            if (ok && pool != null && !pool.isClosed()) {
                pool.recycle(active.lease);
                return;
            }
            if (pool != null) {
                pool.discard(active.lease);
            } else {
                tryClose(active.lease);
            }
        });
    }

    static boolean sameAddress(InetSocketAddress a, InetSocketAddress b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null || a.getPort() != b.getPort()) {
            return false;
        }
        if (a.getAddress() != null && b.getAddress() != null) {
            return a.getAddress().equals(b.getAddress());
        }
        return a.getHostString().equalsIgnoreCase(b.getHostString());
    }

    private void addRequestRedundantPeer(Channel channel, InetSocketAddress relayAddr) {
        if (config instanceof SocksConfig
                && UdpRedundantSupport.isConfigured((SocksConfig) config)
                && UdpRedundantSupport.allowSocksUdpRequest((SocksConfig) config)) {
            UdpRelayAttributes.addRedundantPeer(channel, relayAddr);
        }
    }

    static final class RpcRelayGroupControl {
        final SocksRpcContract facade;
        final String groupId;
        final String token;
        final AtomicBoolean closed = new AtomicBoolean();

        RpcRelayGroupControl(SocksRpcContract facade, String groupId, String token) {
            this.facade = facade;
            this.groupId = groupId;
            this.token = token;
        }

        UdpRelayGroupUpdateResult addRelays(int count) {
            if (closed.get()) {
                return UdpRelayGroupUpdateResult.fail("GROUP_CLOSED", "group is closed");
            }
            try {
                return facade.addUdpRelays(groupId, count, token);
            } catch (Throwable e) {
                DiagnosticMetrics.record("socks.udp.relay.group.add.count", 1D,
                        "result=fail,reason=rpc-error");
                if (e instanceof RuntimeException) {
                    throw (RuntimeException) e;
                }
                throw new IllegalStateException(e);
            }
        }

        boolean removeRelay(int relayPort) {
            if (relayPort <= 0 || closed.get()) {
                return false;
            }
            try {
                return facade.removeUdpRelay(groupId, relayPort, token);
            } catch (Throwable e) {
                DiagnosticMetrics.record("socks.udp.relay.group.remove.count", 1D,
                        "result=fail,reason=rpc-error");
                return false;
            }
        }

        boolean heartbeat() {
            if (closed.get()) {
                return false;
            }
            try {
                return facade.heartbeatUdpRelayGroup(groupId, token);
            } catch (Throwable e) {
                DiagnosticMetrics.record("socks.udp.relay.group.heartbeat.count", 1D,
                        "result=fail,reason=rpc-error");
                return false;
            }
        }

        boolean closeGroup() {
            if (!closed.compareAndSet(false, true)) {
                return true;
            }
            try {
                return facade.closeUdpRelayGroup(groupId, token);
            } catch (Throwable e) {
                DiagnosticMetrics.record("socks.udp.relay.group.close.count", 1D,
                        "result=fail,reason=rpc-error");
                return false;
            }
        }
    }

    static final class SessionGroup {
        volatile SessionHolder[] holders;
        final UdpPortHoppingMode mode;
        final boolean adaptive;
        final long scaleUpBytes;
        final long scaleUpActiveMillis;
        final long scaleUpCooldownMillis;
        final long createdAtMillis;
        final RpcRelayGroupControl rpcControl;
        final AtomicBoolean activeRetained = new AtomicBoolean();
        final AtomicBoolean expanding = new AtomicBoolean();
        final AtomicBoolean replenishing = new AtomicBoolean();
        final AtomicBoolean heartbeating = new AtomicBoolean();
        volatile UpstreamSupport activeSupport;
        volatile int targetHopCount;
        int nextIndex;
        long totalBytes;
        long nextScaleUpBytes;
        long nextScaleUpAtMillis;
        long lastScaleUpAttemptAtMillis;
        long lastReplenishAttemptAtMillis;
        long lastHeartbeatAtMillis;
        int heartbeatFailures;

        SessionGroup(SessionHolder[] holders, UdpPortHoppingMode mode) {
            this(holders, mode, holders != null ? holders.length : 0);
        }

        SessionGroup(SessionHolder[] holders, UdpPortHoppingMode mode, int targetHopCount) {
            this(holders, mode, targetHopCount, null);
        }

        SessionGroup(SessionHolder[] holders, UdpPortHoppingMode mode, int targetHopCount,
                RpcRelayGroupControl rpcControl) {
            this(holders, mode, false, 0L, 0L, 0, targetHopCount, rpcControl);
        }

        SessionGroup(SessionHolder[] holders, UdpPortHoppingMode mode, boolean adaptive,
                     long scaleUpBytes, long scaleUpActiveMillis, int scaleUpCooldownMillis) {
            this(holders, mode, adaptive, scaleUpBytes, scaleUpActiveMillis, scaleUpCooldownMillis,
                    holders != null ? holders.length : 0, null);
        }

        SessionGroup(SessionHolder[] holders, UdpPortHoppingMode mode, boolean adaptive,
                     long scaleUpBytes, long scaleUpActiveMillis, int scaleUpCooldownMillis, int targetHopCount) {
            this(holders, mode, adaptive, scaleUpBytes, scaleUpActiveMillis, scaleUpCooldownMillis,
                    targetHopCount, null);
        }

        SessionGroup(SessionHolder[] holders, UdpPortHoppingMode mode, boolean adaptive,
                     long scaleUpBytes, long scaleUpActiveMillis, int scaleUpCooldownMillis, int targetHopCount,
                     RpcRelayGroupControl rpcControl) {
            this.holders = holders != null ? holders : new SessionHolder[0];
            this.mode = mode != null ? mode : UdpPortHoppingMode.ROUND_ROBIN;
            this.adaptive = adaptive;
            this.targetHopCount = Math.max(this.holders.length, targetHopCount);
            this.scaleUpBytes = Math.max(0L, scaleUpBytes);
            this.scaleUpActiveMillis = Math.max(0L, scaleUpActiveMillis);
            this.scaleUpCooldownMillis = Math.max(0L, scaleUpCooldownMillis);
            this.rpcControl = rpcControl;
            this.createdAtMillis = System.currentTimeMillis();
            this.lastHeartbeatAtMillis = this.createdAtMillis;
            this.nextScaleUpBytes = this.scaleUpBytes;
            this.nextScaleUpAtMillis = this.scaleUpActiveMillis > 0
                    ? createdAtMillis + this.scaleUpActiveMillis : Long.MAX_VALUE;
        }

        boolean isValid() {
            return activeCount() > 0;
        }

        int holderCount() {
            return holders.length;
        }

        int activeCount() {
            int count = 0;
            for (SessionHolder holder : holders) {
                if (holder != null && holder.isValid()) {
                    count++;
                }
            }
            return count;
        }

        boolean hasPooledHolder() {
            for (SessionHolder holder : holders) {
                if (holder != null && holder.pooled) {
                    return true;
                }
            }
            return false;
        }

        InetSocketAddress primaryRelayAddress() {
            for (SessionHolder holder : holders) {
                if (holder != null && holder.isValid()) {
                    return holder.relayAddr;
                }
            }
            return null;
        }

        InetSocketAddress selectRelayAddress() {
            int len = holders.length;
            if (len == 0) {
                return null;
            }
            int start = mode == UdpPortHoppingMode.RANDOM
                    ? ThreadLocalRandom.current().nextInt(len)
                    : nextIndex++;
            for (int i = 0; i < len; i++) {
                int index = (start + i) & Integer.MAX_VALUE;
                SessionHolder holder = holders[index % len];
                if (holder != null && holder.isValid()) {
                    return holder.relayAddr;
                }
            }
            return null;
        }

        boolean containsRelayAddress(InetSocketAddress sender) {
            for (SessionHolder holder : holders) {
                if (holder != null && holder.isValid() && sameAddress(holder.relayAddr, sender)) {
                    return true;
                }
            }
            return false;
        }

        InetSocketAddress[] snapshotRelayAddresses() {
            InetSocketAddress[] addresses = new InetSocketAddress[holders.length];
            int count = 0;
            for (SessionHolder holder : holders) {
                if (holder != null && holder.isValid()) {
                    addresses[count++] = holder.relayAddr;
                }
            }
            return count == addresses.length ? addresses : Arrays.copyOf(addresses, count);
        }

        void addHolder(SessionHolder holder) {
            SessionHolder[] current = holders;
            SessionHolder[] next = Arrays.copyOf(current, current.length + 1);
            next[current.length] = holder;
            holders = next;
            if (targetHopCount < next.length) {
                targetHopCount = next.length;
            }
        }

        boolean removeHolder(SessionHolder holder) {
            if (holder == null) {
                return false;
            }
            SessionHolder[] current = holders;
            int index = -1;
            for (int i = 0; i < current.length; i++) {
                if (current[i] == holder) {
                    index = i;
                    break;
                }
            }
            if (index < 0) {
                return false;
            }
            SessionHolder[] next = new SessionHolder[current.length - 1];
            if (index > 0) {
                System.arraycopy(current, 0, next, 0, index);
            }
            if (index + 1 < current.length) {
                System.arraycopy(current, index + 1, next, index, current.length - index - 1);
            }
            holders = next;
            return true;
        }

        boolean shouldInvalidate(int minActiveHops) {
            return activeCount() < Math.max(1, minActiveHops);
        }

        boolean needsReplenish() {
            return holderCount() < targetHopCount;
        }

        boolean tryBeginReplenish(long nowMillis, int replenishDelayMillis) {
            if (!needsReplenish()) {
                return false;
            }
            if (replenishDelayMillis > 0
                    && lastReplenishAttemptAtMillis > 0L
                    && nowMillis - lastReplenishAttemptAtMillis < replenishDelayMillis) {
                return false;
            }
            if (!replenishing.compareAndSet(false, true)) {
                return false;
            }
            lastReplenishAttemptAtMillis = nowMillis;
            return true;
        }

        void finishReplenish(boolean success, long nowMillis) {
            if (success && holderCount() >= targetHopCount) {
                lastReplenishAttemptAtMillis = 0L;
            } else {
                lastReplenishAttemptAtMillis = nowMillis;
            }
            replenishing.set(false);
        }

        boolean tryBeginHeartbeat(long nowMillis, long intervalMillis) {
            if (nowMillis - lastHeartbeatAtMillis < intervalMillis) {
                return false;
            }
            if (!heartbeating.compareAndSet(false, true)) {
                return false;
            }
            lastHeartbeatAtMillis = nowMillis;
            return true;
        }

        boolean finishHeartbeat(boolean success, int failureThreshold) {
            DiagnosticMetrics.record("socks.udp.relay.group.heartbeat.count", 1D,
                    "result=" + (success ? "success" : "fail"));
            if (success) {
                heartbeatFailures = 0;
            } else {
                heartbeatFailures++;
            }
            heartbeating.set(false);
            return !success && heartbeatFailures >= Math.max(1, failureThreshold);
        }

        InetSocketAddress[] snapshotAllRelayAddresses() {
            InetSocketAddress[] addresses = new InetSocketAddress[holders.length];
            int count = 0;
            for (SessionHolder holder : holders) {
                if (holder != null && holder.relayAddr != null) {
                    addresses[count++] = holder.relayAddr;
                }
            }
            return count == addresses.length ? addresses : Arrays.copyOf(addresses, count);
        }

        void recordBytes(int bytes) {
            if (adaptive && bytes > 0) {
                totalBytes += bytes;
            }
        }

        boolean tryBeginScaleUp(long nowMillis) {
            if (!adaptive || (scaleUpBytes <= 0L && scaleUpActiveMillis <= 0L)) {
                return false;
            }
            boolean bytesDue = scaleUpBytes > 0L && totalBytes >= nextScaleUpBytes;
            boolean timeDue = scaleUpActiveMillis > 0L && nowMillis >= nextScaleUpAtMillis;
            if (!bytesDue && !timeDue) {
                return false;
            }
            if (scaleUpCooldownMillis > 0L
                    && lastScaleUpAttemptAtMillis > 0L
                    && nowMillis - lastScaleUpAttemptAtMillis < scaleUpCooldownMillis) {
                return false;
            }
            if (!expanding.compareAndSet(false, true)) {
                return false;
            }
            lastScaleUpAttemptAtMillis = nowMillis;
            return true;
        }

        void finishScaleUp(boolean success, long nowMillis) {
            if (success) {
                if (scaleUpBytes > 0L) {
                    nextScaleUpBytes += scaleUpBytes;
                }
                if (scaleUpActiveMillis > 0L) {
                    nextScaleUpAtMillis += scaleUpActiveMillis;
                    if (nextScaleUpAtMillis < nowMillis) {
                        nextScaleUpAtMillis = nowMillis + scaleUpActiveMillis;
                    }
                }
            }
            expanding.set(false);
        }

        void retain(UpstreamSupport support) {
            if (support != null && activeRetained.compareAndSet(false, true)) {
                activeSupport = support;
                support.retainConnection();
            }
        }

        void releaseActive() {
            UpstreamSupport support = activeSupport;
            if (support != null && activeRetained.compareAndSet(true, false)) {
                support.releaseConnection();
                activeSupport = null;
            }
        }
    }

    static final class SessionHolder {
        final Socks5Client client;
        final Socks5UdpSession session;
        final Socks5UdpLease lease;
        final Socks5UpstreamPoolManager.UdpLeasePool pool;
        final InetSocketAddress relayAddr;
        final RpcRelayGroupControl rpcControl;
        final String relayId;
        final boolean pooled;

        static SessionHolder session(Socks5Client client, Socks5UdpSession session) {
            return new SessionHolder(client, session, null, null, session.getRelayAddress(), false);
        }

        static SessionHolder pooled(Socks5UpstreamPoolManager.UdpLeasePool pool, Socks5UdpLease lease) {
            return new SessionHolder(null, null, lease, pool, lease.getRelayAddress(), true);
        }

        static SessionHolder rpc(RpcRelayGroupControl control, String relayId, InetSocketAddress relayAddr) {
            return new SessionHolder(null, null, null, null, relayAddr, false, control, relayId);
        }

        SessionHolder(Socks5Client client, Socks5UdpSession session, Socks5UdpLease lease,
                      Socks5UpstreamPoolManager.UdpLeasePool pool, InetSocketAddress relayAddr, boolean pooled) {
            this(client, session, lease, pool, relayAddr, pooled, null, null);
        }

        SessionHolder(Socks5Client client, Socks5UdpSession session, Socks5UdpLease lease,
                      Socks5UpstreamPoolManager.UdpLeasePool pool, InetSocketAddress relayAddr, boolean pooled,
                      RpcRelayGroupControl rpcControl, String relayId) {
            this.client = client;
            this.session = session;
            this.lease = lease;
            this.pool = pool;
            this.relayAddr = relayAddr;
            this.pooled = pooled;
            this.rpcControl = rpcControl;
            this.relayId = relayId;
        }

        boolean isValid() {
            if (rpcControl != null) {
                return relayAddr != null && !rpcControl.closed.get();
            }
            if (!pooled) {
                return session != null && !session.isClosed();
            }
            return lease != null && !lease.isClosed() && lease.getTcpControl().isActive();
        }

        Channel controlChannel() {
            if (!pooled) {
                return session != null ? session.getTcpControl() : null;
            }
            return lease != null ? lease.getTcpControl() : null;
        }
    }
}
