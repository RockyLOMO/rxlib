package org.rx.net.socks;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Cache;
import org.rx.core.CachePolicy;
import org.rx.core.Disposable;
import org.rx.core.ObjectPool;
import org.rx.core.Tasks;
import org.rx.core.cache.MemoryCache;
import org.rx.diagnostic.DiagnosticMetrics;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.Sockets;
import org.rx.net.support.UpstreamSupport;
import org.rx.net.socks.Socks5Client.Socks5UdpLease;
import org.rx.net.socks.upstream.SocksTcpUpstream;
import org.rx.net.socks.upstream.SocksUdpUpstream;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Demand-driven TCP warm pool &amp; UDP lease pool manager.
 * <ul>
 *     <li>Uses a single shared lazy maintenance scheduler across all TCP warm pools (zero per-pool timers;
 *         cancelled automatically when no pools remain).</li>
 *     <li>Idle pools shrink target capacity to 0 and stop dialing once inactive past {@code maxIdleMillis}.</li>
 *     <li>Enforces process-wide budgets on pool key count, ready warm channels, and concurrent in-flight dials.</li>
 *     <li>Wires route-generation-scoped {@link UpstreamSupport} onto warmed channels for adaptive UDP health feedback.</li>
 * </ul>
 */
@Slf4j
public final class Socks5UpstreamPoolManager extends Disposable {
    public static final Socks5UpstreamPoolManager INSTANCE = new Socks5UpstreamPoolManager();
    public static final int DEFAULT_MAX_TCP_POOLS = 64;
    public static final int DEFAULT_MAX_TOTAL_WARM_CHANNELS = 64;
    public static final int DEFAULT_MAX_IN_FLIGHT_CREATES = 8;

    @Getter
    @RequiredArgsConstructor
    public static final class TcpWarmPoolStats {
        private final int poolCount;
        private final int readyChannels;
        private final int inFlightCreates;
        private final long hits;
        private final long misses;
        private final long unusedExpired;
        private final long createFailures;
        private final boolean schedulerActive;
    }

    public static final class TcpWarmPool extends Disposable {
        final Socks5UpstreamPoolManager owner;
        final TcpWarmPoolKey key;
        final SocksConfig config;
        final AuthenticEndpoint serverEndpoint;
        final InetSocketAddress adaptiveUdpClient;
        final UpstreamSupport upstreamSupport;
        final ConcurrentLinkedDeque<Channel> readyChannels = new ConcurrentLinkedDeque<>();
        final AtomicInteger createFailures = new AtomicInteger();
        final AtomicBoolean refilling = new AtomicBoolean();
        final AtomicLong hits = new AtomicLong();
        final AtomicLong misses = new AtomicLong();
        final AtomicLong unusedExpired = new AtomicLong();
        volatile long lastBorrowAtMillis;
        volatile long nextRefillAt;

        TcpWarmPool(Socks5UpstreamPoolManager owner, TcpWarmPoolKey key, SocksConfig config,
                    AuthenticEndpoint serverEndpoint, InetSocketAddress adaptiveUdpClient,
                    UpstreamSupport upstreamSupport) {
            this.owner = owner;
            this.key = key;
            this.config = config;
            this.serverEndpoint = serverEndpoint;
            this.adaptiveUdpClient = adaptiveUdpClient;
            this.upstreamSupport = upstreamSupport;
            this.lastBorrowAtMillis = System.currentTimeMillis();
        }

        Channel borrow() {
            lastBorrowAtMillis = System.currentTimeMillis();
            for (; ; ) {
                Channel channel = readyChannels.pollFirst();
                if (channel == null) {
                    misses.incrementAndGet();
                    owner.totalMisses.incrementAndGet();
                    if (DiagnosticMetrics.isEnabled()) {
                        DiagnosticMetrics.record("socks.tcp.warm.miss.count", 1D, "key=" + key);
                    }
                    scheduleDemandRefill();
                    return null;
                }
                owner.totalWarmChannels.updateAndGet(v -> v > 0 ? v - 1 : 0);
                Socks5WarmupHandler handler = channel.pipeline().get(Socks5WarmupHandler.class);
                if (handler == null || !channel.isActive()) {
                    retire(channel, "inactive", false);
                    continue;
                }
                long age = System.currentTimeMillis() - handler.getReadyAtMillis();
                if (age >= config.getTcpWarmPoolMaxIdleMillis()) {
                    retire(channel, "stale-age", true);
                    continue;
                }
                hits.incrementAndGet();
                owner.totalHits.incrementAndGet();
                if (DiagnosticMetrics.isEnabled()) {
                    DiagnosticMetrics.record("socks.tcp.warm.hit.count", 1D, "key=" + key);
                }
                if (readyChannels.size() < targetSize(System.currentTimeMillis())) {
                    scheduleDemandRefill();
                }
                return channel;
            }
        }

        int targetSize(long nowMillis) {
            if (isClosed() || (upstreamSupport != null && upstreamSupport.isClosed())) {
                return 0;
            }
            int configured = Math.max(0, config.getTcpWarmPoolMinSize());
            if (configured == 0) {
                return 0;
            }
            long idleWindow = Math.max(1000L, config.getTcpWarmPoolMaxIdleMillis());
            if (nowMillis - lastBorrowAtMillis >= idleWindow) {
                // Idle upstream: shrink warm target to 0 and stop dialing until new demand arrives.
                return 0;
            }
            return configured;
        }

        boolean isIdleExpired(long nowMillis) {
            long idleWindow = Math.max(1000L, config.getTcpWarmPoolMaxIdleMillis());
            return readyChannels.isEmpty() && !refilling.get() && nowMillis - lastBorrowAtMillis >= idleWindow * 2L;
        }

        void maintain(long nowMillis) {
            if (isClosed()) {
                return;
            }
            // Evict expired or inactive channels first so unused connections never linger.
            pruneStale(nowMillis);
            int target = targetSize(nowMillis);
            if (target == 0) {
                // Drain surplus ready channels once the pool goes idle.
                Channel surplus;
                while ((surplus = readyChannels.pollFirst()) != null) {
                    owner.totalWarmChannels.updateAndGet(v -> v > 0 ? v - 1 : 0);
                    retire(surplus, "idle-shrink", true);
                }
                return;
            }
            if (nowMillis < nextRefillAt || readyChannels.size() >= target) {
                return;
            }
            scheduleDemandRefill();
        }

        private void pruneStale(long nowMillis) {
            long maxIdle = Math.max(1000L, config.getTcpWarmPoolMaxIdleMillis());
            for (Channel ch : readyChannels) {
                Socks5WarmupHandler h = ch.pipeline().get(Socks5WarmupHandler.class);
                boolean inactive = h == null || !ch.isActive();
                boolean expired = !inactive && (nowMillis - h.getReadyAtMillis() >= maxIdle);
                if ((inactive || expired) && readyChannels.remove(ch)) {
                    owner.totalWarmChannels.updateAndGet(v -> v > 0 ? v - 1 : 0);
                    retire(ch, inactive ? "inactive" : "stale-age", expired);
                }
            }
        }

        private void scheduleDemandRefill() {
            if (isClosed() || !refilling.compareAndSet(false, true)) {
                return;
            }
            boolean submitted = false;
            try {
                submitted = Tasks.tryExecute(() -> {
                    try {
                        refill();
                    } finally {
                        refilling.set(false);
                    }
                });
            } finally {
                if (!submitted) {
                    refilling.set(false);
                }
            }
        }

        void refill() {
            if (isClosed()) {
                return;
            }
            long now = System.currentTimeMillis();
            if (now < nextRefillAt) {
                return;
            }
            int target = targetSize(now);
            while (!isClosed() && readyChannels.size() < target) {
                if (!tryReserve(owner.totalWarmChannels, owner.maxTotalWarmChannels)) {
                    break;
                }
                if (!tryReserve(owner.inFlightCreates, owner.maxInFlightCreates)) {
                    owner.totalWarmChannels.updateAndGet(v -> v > 0 ? v - 1 : 0);
                    break;
                }
                Channel channel = null;
                try {
                    channel = createWarmChannel();
                } finally {
                    owner.inFlightCreates.updateAndGet(v -> v > 0 ? v - 1 : 0);
                }
                if (channel == null) {
                    owner.totalWarmChannels.updateAndGet(v -> v > 0 ? v - 1 : 0);
                    owner.totalCreateFailures.incrementAndGet();
                    int failures = Math.min(createFailures.incrementAndGet(), 10);
                    long delay = Math.min(config.getTcpWarmPoolRefillIntervalMillis() * (1L << failures), 30_000L);
                    nextRefillAt = System.currentTimeMillis() + delay;
                    if (DiagnosticMetrics.isEnabled()) {
                        DiagnosticMetrics.record("socks.tcp.warm.create.backoff.count", 1D, "key=" + key + ",delay=" + delay);
                    }
                    break;
                }
                if (isClosed() || targetSize(System.currentTimeMillis()) == 0) {
                    owner.totalWarmChannels.updateAndGet(v -> v > 0 ? v - 1 : 0);
                    retire(channel, "closed-during-create", false);
                    break;
                }
                createFailures.set(0);
                nextRefillAt = 0L;
                readyChannels.offerLast(channel);
                target = targetSize(System.currentTimeMillis());
            }
        }

        private Channel createWarmChannel() {
            Socks5WarmupHandler handler = new Socks5WarmupHandler(serverEndpoint.getConnectEndpoint(), serverEndpoint.getUsername(),
                    serverEndpoint.getPassword(), config.getConnectTimeoutMillis());
            handler.setAdaptiveTcp(config.isAdaptiveTcp());
            handler.setAdaptiveProfile(config.getAdaptiveProfile());
            handler.setUdpHop(config.isUdpHop());
            handler.setAdaptiveUdpMtu(config.resolveDailyUdpMtu());
            handler.setAdaptiveUdpAddress(adaptiveUdpClient);
            handler.setUpstreamSupport(upstreamSupport);
            try {
                ChannelFuture connectFuture = Sockets.bootstrap(config, serverEndpoint.getConnectEndpoint(), ch -> {
                    Sockets.addTcpClientHandler(ch, config, serverEndpoint.getInetEndpoint());
                    SocksConnectionTagRegistry.bindOnActive(ch, key.getTrafficUser());
                    ch.pipeline().addLast(handler);
                }).connect(serverEndpoint.getConnectEndpoint());
                long timeout = Math.max(config.getConnectTimeoutMillis(), 1000);
                connectFuture.get(timeout, TimeUnit.MILLISECONDS);
                return handler.readyFuture().get(timeout, TimeUnit.MILLISECONDS);
            } catch (Throwable e) {
                log.warn("create warm channel fail {}", serverEndpoint, e);
                if (handler.readyFuture().isDone()) {
                    try {
                        Channel ch = handler.readyFuture().getNow(null);
                        if (ch != null) {
                            Sockets.closeOnFlushed(ch);
                        }
                    } catch (Throwable ignored) {
                    }
                }
                return null;
            }
        }

        void retire(Channel channel, String reason, boolean countUnused) {
            if (countUnused) {
                unusedExpired.incrementAndGet();
                owner.totalUnusedExpired.incrementAndGet();
                if (DiagnosticMetrics.isEnabled()) {
                    DiagnosticMetrics.record("socks.tcp.warm.unused.expired.count", 1D, "key=" + key + ",reason=" + reason);
                }
            }
            if (DiagnosticMetrics.isEnabled()) {
                DiagnosticMetrics.record("socks.tcp.warm.retire.count", 1D, "key=" + key + ",reason=" + reason);
            }
            Sockets.closeOnFlushed(channel);
        }

        @Override
        protected void dispose() {
            Channel channel;
            while ((channel = readyChannels.pollFirst()) != null) {
                owner.totalWarmChannels.updateAndGet(v -> v > 0 ? v - 1 : 0);
                Sockets.closeOnFlushed(channel);
            }
        }
    }

    @RequiredArgsConstructor
    public static final class UdpLeasePool extends Disposable {
        final UdpLeasePoolKey key;
        final SocksConfig config;
        final AuthenticEndpoint serverEndpoint;
        final long effectiveIdleMillis;
        final ObjectPool<Socks5UdpLease> delegate;
        final AtomicInteger borrowedLeases = new AtomicInteger();

        public Socks5UdpLease borrow() {
            try {
                Socks5UdpLease lease = delegate.borrow();
                if (lease != null) {
                    borrowedLeases.incrementAndGet();
                    DiagnosticMetrics.record("socks.udp.lease.pool.borrow.count", 1D,
                            "result=hit");
                    recordState("borrow-hit");
                }
                return lease;
            } catch (TimeoutException e) {
                DiagnosticMetrics.record("socks.udp.lease.pool.borrow.count", 1D,
                        "result=miss");
                recordState("borrow-miss");
                return null;
            }
        }

        public void recycle(Socks5UdpLease lease) {
            borrowedLeases.updateAndGet(v -> v > 0 ? v - 1 : 0);
            delegate.recycle(lease);
            DiagnosticMetrics.record("socks.udp.lease.pool.recycle.count", 1D,
                    "result=success");
            recordState("recycle");
        }

        public void discard(Socks5UdpLease lease) {
            borrowedLeases.updateAndGet(v -> v > 0 ? v - 1 : 0);
            if (lease != null) {
                lease.close();
                try {
                    delegate.recycle(lease);
                } catch (Throwable e) {
                    log.warn("discard udp lease fail {}", key, e);
                }
            }
            DiagnosticMetrics.record("socks.udp.lease.pool.recycle.count", 1D,
                    "result=discard");
            recordState("discard");
        }

        void recordState(String action) {
            DiagnosticMetrics.record("socks.udp.lease.pool.idle.count", delegate.idleSize(),
                    "action=" + action);
            DiagnosticMetrics.record("socks.udp.lease.pool.borrowed.count", borrowedLeases.get(),
                    "action=" + action);
        }

        @Override
        protected void dispose() {
            delegate.close();
        }
    }

    private final ConcurrentMap<TcpWarmPoolKey, TcpWarmPool> tcpPools = new ConcurrentHashMap<>();
    private final ConcurrentMap<UdpLeasePoolKey, UdpLeasePool> udpPools = new ConcurrentHashMap<>();
    private final ConcurrentMap<UdpLeasePoolKey, AtomicInteger> udpRpcFailures = new ConcurrentHashMap<>();
    private final Cache<UdpLeasePoolKey, Boolean> breakerCache = new MemoryCache<>();
    private final AtomicInteger totalWarmChannels = new AtomicInteger();
    private final AtomicInteger inFlightCreates = new AtomicInteger();
    private final AtomicLong totalHits = new AtomicLong();
    private final AtomicLong totalMisses = new AtomicLong();
    private final AtomicLong totalUnusedExpired = new AtomicLong();
    private final AtomicLong totalCreateFailures = new AtomicLong();
    private final Object schedulerLock = new Object();
    private volatile ScheduledFuture<?> sharedMaintenanceTask;
    volatile int maxTcpPools = DEFAULT_MAX_TCP_POOLS;
    volatile int maxTotalWarmChannels = DEFAULT_MAX_TOTAL_WARM_CHANNELS;
    volatile int maxInFlightCreates = DEFAULT_MAX_IN_FLIGHT_CREATES;

    private Socks5UpstreamPoolManager() {
    }

    public TcpWarmPoolStats tcpWarmPoolStats() {
        ScheduledFuture<?> task = sharedMaintenanceTask;
        return new TcpWarmPoolStats(
                tcpPools.size(),
                totalWarmChannels.get(),
                inFlightCreates.get(),
                totalHits.get(),
                totalMisses.get(),
                totalUnusedExpired.get(),
                totalCreateFailures.get(),
                task != null && !task.isCancelled());
    }

    public Channel borrowWarmChannel(SocksTcpUpstream upstream) {
        SocksConfig config = (SocksConfig) upstream.getConfig();
        if (config == null || !config.isTcpWarmPoolEnabled()) {
            return null;
        }
        TcpWarmPoolKey key = upstream.warmPoolKey();
        TcpWarmPool pool = tcpPools.get(key);
        if (pool == null) {
            evictIfPoolCapReached();
            pool = tcpPools.computeIfAbsent(key, k -> new TcpWarmPool(
                    this, k, config, upstream.getServerEndpoint(),
                    upstream.getAdaptiveUdpClient(), upstream.getUpstreamSupport()));
            ensureSharedMaintenanceStarted(config.getTcpWarmPoolRefillIntervalMillis());
        }
        return pool.borrow();
    }

    private void evictIfPoolCapReached() {
        if (tcpPools.size() < maxTcpPools) {
            return;
        }
        TcpWarmPoolKey oldestKey = null;
        TcpWarmPool oldestPool = null;
        long oldestBorrow = Long.MAX_VALUE;
        for (Map.Entry<TcpWarmPoolKey, TcpWarmPool> entry : tcpPools.entrySet()) {
            long ts = entry.getValue().lastBorrowAtMillis;
            if (ts < oldestBorrow) {
                oldestBorrow = ts;
                oldestKey = entry.getKey();
                oldestPool = entry.getValue();
            }
        }
        if (oldestKey != null && tcpPools.remove(oldestKey, oldestPool)) {
            oldestPool.close();
        }
    }

    private void ensureSharedMaintenanceStarted(int hintIntervalMillis) {
        if (sharedMaintenanceTask != null && !sharedMaintenanceTask.isCancelled()) {
            return;
        }
        synchronized (schedulerLock) {
            if (sharedMaintenanceTask != null && !sharedMaintenanceTask.isCancelled()) {
                return;
            }
            long period = Math.max(500L, Math.min(5000L, hintIntervalMillis > 0 ? hintIntervalMillis : 1000L));
            sharedMaintenanceTask = Tasks.schedulePeriod(this::maintainTcpPools, period);
        }
    }

    private void stopSharedMaintenanceIfEmpty() {
        if (!tcpPools.isEmpty()) {
            return;
        }
        synchronized (schedulerLock) {
            if (tcpPools.isEmpty() && sharedMaintenanceTask != null) {
                sharedMaintenanceTask.cancel(false);
                sharedMaintenanceTask = null;
            }
        }
    }

    void maintainTcpPools() {
        if (tcpPools.isEmpty()) {
            stopSharedMaintenanceIfEmpty();
            return;
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<TcpWarmPoolKey, TcpWarmPool> entry : tcpPools.entrySet()) {
            TcpWarmPool pool = entry.getValue();
            pool.maintain(now);
            if (pool.isIdleExpired(now) && tcpPools.remove(entry.getKey(), pool)) {
                pool.close();
            }
        }
        if (tcpPools.isEmpty()) {
            stopSharedMaintenanceIfEmpty();
        }
    }

    private static boolean tryReserve(AtomicInteger counter, int limit) {
        while (true) {
            int cur = counter.get();
            if (cur >= limit) {
                return false;
            }
            if (counter.compareAndSet(cur, cur + 1)) {
                return true;
            }
        }
    }

    public UdpLeasePool udpPool(SocksUdpUpstream upstream) {
        SocksConfig config = (SocksConfig) upstream.getConfig();
        if (config == null || !config.isUdpLeasePoolEnabled()) {
            return null;
        }
        long idleHint = upstream.resolveRelayIdleHintMillis();
        if (idleHint > 0 && idleHint < 1000L) {
            if (DiagnosticMetrics.isEnabled()) {
                DiagnosticMetrics.record("socks.udp.lease.disabled.count", 1D, "reason=remote-idle");
            }
            return null;
        }
        long effectiveIdle = idleHint > 0 ? Math.min(config.getUdpLeasePoolMaxIdleMillis(), idleHint) : config.getUdpLeasePoolMaxIdleMillis();
        UdpLeasePoolKey key = upstream.poolKey();
        return udpPools.computeIfAbsent(key, k -> {
            ObjectPool<Socks5UdpLease> pool = new ObjectPool<>(config.getUdpLeasePoolMinSize(), config.getUdpLeasePoolMaxSize(),
                    () -> {
                        Socks5Client client = new Socks5Client(upstream.getServerEndpoint(), config);
                        long timeout = Math.max(config.getConnectTimeoutMillis(), 1000);
                        return client.udpAssociateLeaseAsync().get(timeout, TimeUnit.MILLISECONDS);
                    },
                    lease -> lease != null && !lease.isClosed() && lease.getTcpControl().isActive());
            pool.setBorrowTimeout(config.getConnectTimeoutMillis());
            pool.setIdleTimeout(effectiveIdle);
            return new UdpLeasePool(k, config, upstream.getServerEndpoint(), effectiveIdle, pool);
        });
    }

    public boolean isUdpBreakerOpen(UdpLeasePoolKey key) {
        return breakerCache.containsKey(key);
    }

    public void onUdpRpcSuccess(UdpLeasePoolKey key) {
        udpRpcFailures.remove(key);
        breakerCache.remove(key);
    }

    public void onUdpRpcFailure(UdpLeasePoolKey key, SocksConfig config, String phase, Throwable cause) {
        AtomicInteger failures = udpRpcFailures.computeIfAbsent(key, k -> new AtomicInteger());
        int count = failures.incrementAndGet();
        if (DiagnosticMetrics.isEnabled()) {
            DiagnosticMetrics.record("socks.udp.lease.rpc.fail.count", 1D, "phase=" + phase);
        }
        if (count >= config.getUdpLeaseRpcBreakerThreshold()) {
            breakerCache.put(key, Boolean.TRUE,
                    CachePolicy.absolute(config.getUdpLeaseRpcBreakerOpenSeconds()));
            failures.set(0);
            if (DiagnosticMetrics.isEnabled()) {
                DiagnosticMetrics.record("socks.udp.lease.breaker.open.count", 1D, "action=open");
            }
        }
        if (cause != null) {
            log.warn("udp lease rpc {} fail {}", phase, key, cause);
        }
    }

    public void closeEndpoint(AuthenticEndpoint endpoint) {
        for (Map.Entry<TcpWarmPoolKey, TcpWarmPool> entry : tcpPools.entrySet()) {
            if (entry.getKey().isSameEndpoint(endpoint) && tcpPools.remove(entry.getKey(), entry.getValue())) {
                entry.getValue().close();
            }
        }
        stopSharedMaintenanceIfEmpty();
        for (Map.Entry<UdpLeasePoolKey, UdpLeasePool> entry : udpPools.entrySet()) {
            if (entry.getKey().isSameEndpoint(endpoint) && udpPools.remove(entry.getKey(), entry.getValue())) {
                entry.getValue().close();
            }
        }
    }

    @Override
    protected void dispose() {
        closeAll();
    }

    public void closeAll() {
        for (TcpWarmPool pool : tcpPools.values()) {
            pool.close();
        }
        tcpPools.clear();
        stopSharedMaintenanceIfEmpty();
        for (UdpLeasePool pool : udpPools.values()) {
            pool.close();
        }
        udpPools.clear();
        udpRpcFailures.clear();
    }
}
