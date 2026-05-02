package org.rx.net.socks.upstream;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Tasks;
import org.rx.diagnostic.DiagnosticMetrics;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.socks.Socks5Client;
import org.rx.net.socks.Socks5Client.Socks5UdpLease;
import org.rx.net.socks.Socks5Client.Socks5UdpSession;
import org.rx.net.socks.SocksUdpRelayHandler;
import org.rx.net.socks.Socks5UpstreamPoolManager;
import org.rx.net.socks.SocksConfig;
import org.rx.net.socks.SocksRpcContract;
import org.rx.net.socks.UdpRelayAttributes;
import org.rx.net.socks.UdpLeasePoolKey;
import org.rx.net.socks.UdpPortHoppingConfig;
import org.rx.net.socks.UdpPortHoppingMode;
import org.rx.net.support.UnresolvedEndpoint;
import org.rx.net.support.UpstreamSupport;

import java.nio.channels.ClosedChannelException;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.rx.core.Extends.tryClose;

@Slf4j
public class SocksUdpUpstream extends Upstream {
    private static final AttributeKey<SessionGroup> ATTR_UDP_SESSION =
            AttributeKey.valueOf("socksUdpUpstreamSessionGroup");
    private static final AttributeKey<CompletableFuture<SessionGroup>> ATTR_UDP_SESSION_INIT =
            AttributeKey.valueOf("socksUdpUpstreamSessionInit");
    private static final InetSocketAddress[] EMPTY_RELAY_ADDRESSES = new InetSocketAddress[0];

    private final UpstreamSupport next;

    public SocksUdpUpstream(UnresolvedEndpoint dstEp, @NonNull SocksConfig config, @NonNull UpstreamSupport next) {
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
        SessionGroup group = activeGroup(channel);
        if (group == null) {
            return null;
        }
        InetSocketAddress relayAddress = group.selectRelayAddress();
        maybeExpandGroup(channel, group);
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
        maybeExpandGroup(channel, group);
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
            boolean ok = facade.claimUdpRelay(lease.getRelayPort(), clientAddr);
            if (ok) {
                poolManager.onUdpRpcSuccess(poolKey());
                return true;
            }
            poolManager.onUdpRpcFailure(poolKey(), socksConfig, "claim", null);
        } catch (Exception e) {
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
        return udpLocalAddr;
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
                    tryClose(lease);
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
        if (hopCount <= 1) {
            return newSessionGroup(new SessionHolder[]{acquireHolder(channel)}, socksConfig);
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
        return newSessionGroup(Arrays.copyOf(holders, count), socksConfig);
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

    private static SessionGroup newSessionGroup(SessionHolder[] holders, SocksConfig socksConfig) {
        if (!socksConfig.isUdpPortHoppingEnabled()) {
            return new SessionGroup(holders, UdpPortHoppingMode.ROUND_ROBIN);
        }
        return new SessionGroup(holders, socksConfig.getUdpPortHoppingMode(),
                socksConfig.isUdpPortHoppingAdaptive(),
                socksConfig.getUdpPortHoppingAdaptiveScaleUpBytes(),
                socksConfig.getUdpPortHoppingAdaptiveScaleUpActiveMillis(),
                socksConfig.getUdpPortHoppingAdaptiveScaleUpCooldownMillis());
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
        if (holder.pooled) {
            tryClose(holder.lease);
            return;
        }
        tryClose(holder.session);
        tryClose(holder.client);
    }

    private void closeGroup(SessionGroup group, boolean resetPooledLease) {
        if (group == null) {
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
            UdpRelayAttributes.addRedundantPeer(channel, relayAddr);
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
                    channel.eventLoop().execute(() -> invalidateGroup(channel, group, false, "control-close")));
        }
    }

    private void maybeExpandGroup(Channel channel, SessionGroup group) {
        SocksConfig socksConfig = (SocksConfig) config;
        if (!socksConfig.isUdpPortHoppingAdaptive()) {
            return;
        }
        int maxHopCount = clampHopCount(socksConfig.getUdpPortHoppingMaxHopCount());
        if (group.holderCount() >= maxHopCount || !group.tryBeginScaleUp(System.currentTimeMillis())) {
            return;
        }
        Tasks.runAsync(() -> expandGroupOffLoop(channel, group, maxHopCount));
    }

    private void expandGroupOffLoop(Channel channel, SessionGroup group, int maxHopCount) {
        SessionHolder holder = null;
        Throwable error = null;
        try {
            if (group.holderCount() < maxHopCount) {
                holder = acquireHolder(channel);
            }
        } catch (Throwable e) {
            error = e;
        }

        final SessionHolder finalHolder = holder;
        final Throwable finalError = error;
        channel.eventLoop().execute(() -> completeGroupExpand(channel, group, finalHolder, finalError, maxHopCount));
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
            UdpRelayAttributes.addRedundantPeer(channel, holder.relayAddr);
            registerControlCloseListener(channel, group, holder);
            recordGroupActiveCount(channel, group, "adaptive-scale-up");
            DiagnosticMetrics.record("socks.udp.porthop.adaptive.scale.count", 1D,
                    "result=success,hops=" + group.holderCount());
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
            relay.attr(ATTR_UDP_SESSION).set(null);
            active.releaseActive();
            SocksUdpRelayHandler.onUpstreamSessionInvalidated(relay, null, this);
            DiagnosticMetrics.record("socks.udp.session.invalidate.count", 1D,
                    "reason=" + reason + ",pooled=" + active.hasPooledHolder() + ",hops=" + active.holders.length);

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
        int localPort = channel.localAddress() instanceof InetSocketAddress
                ? ((InetSocketAddress) channel.localAddress()).getPort() : -1;
        DiagnosticMetrics.record("socks.udp.porthop.group.active.count", group.activeCount(),
                "action=" + action + ",port=" + localPort);
    }

    private void closeAfterRelayClose(SessionHolder active) {
        if (active == null) {
            return;
        }
        if (!active.pooled) {
            tryClose(active.session);
            tryClose(active.client);
            return;
        }

        SocksRpcContract facade = next.getFacade();
        if (facade == null) {
            tryClose(active.lease);
            return;
        }

        Tasks.runAsync(() -> {
            boolean ok = false;
            try {
                ok = facade.resetUdpRelay(active.lease.getRelayPort());
                if (ok) {
                    Socks5UpstreamPoolManager.INSTANCE.onUdpRpcSuccess(poolKey());
                } else {
                    Socks5UpstreamPoolManager.INSTANCE.onUdpRpcFailure(poolKey(), (SocksConfig) config, "reset", null);
                }
            } catch (Throwable e) {
                Socks5UpstreamPoolManager.INSTANCE.onUdpRpcFailure(poolKey(), (SocksConfig) config, "reset", e);
            }

            Socks5UpstreamPoolManager.UdpLeasePool pool = active.pool;
            if (ok && pool != null && !pool.isClosed()) {
                pool.recycle(active.lease);
                return;
            }
            tryClose(active.lease);
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

    static final class SessionGroup {
        volatile SessionHolder[] holders;
        final UdpPortHoppingMode mode;
        final boolean adaptive;
        final long scaleUpBytes;
        final long scaleUpActiveMillis;
        final long scaleUpCooldownMillis;
        final long createdAtMillis;
        final AtomicBoolean activeRetained = new AtomicBoolean();
        final AtomicBoolean expanding = new AtomicBoolean();
        volatile UpstreamSupport activeSupport;
        int nextIndex;
        long totalBytes;
        long nextScaleUpBytes;
        long nextScaleUpAtMillis;
        long lastScaleUpAttemptAtMillis;

        SessionGroup(SessionHolder[] holders, UdpPortHoppingMode mode) {
            this(holders, mode, false, 0L, 0L, 0);
        }

        SessionGroup(SessionHolder[] holders, UdpPortHoppingMode mode, boolean adaptive,
                     long scaleUpBytes, long scaleUpActiveMillis, int scaleUpCooldownMillis) {
            this.holders = holders != null ? holders : new SessionHolder[0];
            this.mode = mode != null ? mode : UdpPortHoppingMode.ROUND_ROBIN;
            this.adaptive = adaptive;
            this.scaleUpBytes = Math.max(0L, scaleUpBytes);
            this.scaleUpActiveMillis = Math.max(0L, scaleUpActiveMillis);
            this.scaleUpCooldownMillis = Math.max(0L, scaleUpCooldownMillis);
            this.createdAtMillis = System.currentTimeMillis();
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
        final boolean pooled;

        static SessionHolder session(Socks5Client client, Socks5UdpSession session) {
            return new SessionHolder(client, session, null, null, session.getRelayAddress(), false);
        }

        static SessionHolder pooled(Socks5UpstreamPoolManager.UdpLeasePool pool, Socks5UdpLease lease) {
            return new SessionHolder(null, null, lease, pool, lease.getRelayAddress(), true);
        }

        SessionHolder(Socks5Client client, Socks5UdpSession session, Socks5UdpLease lease,
                      Socks5UpstreamPoolManager.UdpLeasePool pool, InetSocketAddress relayAddr, boolean pooled) {
            this.client = client;
            this.session = session;
            this.lease = lease;
            this.pool = pool;
            this.relayAddr = relayAddr;
            this.pooled = pooled;
        }

        boolean isValid() {
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
