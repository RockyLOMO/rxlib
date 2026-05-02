package org.rx.net.socks;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
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
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public final class Socks5UpstreamPoolManager extends Disposable {
    public static final Socks5UpstreamPoolManager INSTANCE = new Socks5UpstreamPoolManager();
    @RequiredArgsConstructor
    public static final class TcpWarmPool extends Disposable {
        final TcpWarmPoolKey key;
        final SocksConfig config;
        final AuthenticEndpoint serverEndpoint;
        final ConcurrentLinkedDeque<Channel> readyChannels = new ConcurrentLinkedDeque<>();
        final AtomicInteger createFailures = new AtomicInteger();
        volatile long nextRefillAt;
        volatile ScheduledFuture<?> refillTask;

        void start() {
            refillTask = Tasks.schedulePeriod(this::refill, config.getTcpWarmPoolRefillIntervalMillis());
        }

        Channel borrow() {
            for (; ; ) {
                Channel channel = readyChannels.pollFirst();
                if (channel == null) {
                    if (DiagnosticMetrics.isEnabled()) {
                        DiagnosticMetrics.record("socks.tcp.warm.miss.count", 1D, "key=" + key);
                    }
                    return null;
                }
                Socks5WarmupHandler handler = channel.pipeline().get(Socks5WarmupHandler.class);
                if (handler == null || !channel.isActive()) {
                    retire(channel, "inactive");
                    continue;
                }
                long age = System.currentTimeMillis() - handler.getReadyAtMillis();
                if (age >= config.getTcpWarmPoolMaxIdleMillis()) {
                    retire(channel, "stale-age");
                    continue;
                }
                if (DiagnosticMetrics.isEnabled()) {
                    DiagnosticMetrics.record("socks.tcp.warm.hit.count", 1D, "key=" + key);
                }
                return channel;
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
            while (readyChannels.size() < config.getTcpWarmPoolMinSize()) {
                Channel channel = createWarmChannel();
                if (channel == null) {
                    int failures = Math.min(createFailures.incrementAndGet(), 10);
                    long delay = Math.min(config.getTcpWarmPoolRefillIntervalMillis() * (1L << failures), 30_000L);
                    nextRefillAt = now + delay;
                    if (DiagnosticMetrics.isEnabled()) {
                        DiagnosticMetrics.record("socks.tcp.warm.create.backoff.count", 1D, "key=" + key + ",delay=" + delay);
                    }
                    break;
                }
                createFailures.set(0);
                nextRefillAt = 0L;
                readyChannels.offerLast(channel);
            }
        }

        private Channel createWarmChannel() {
            Socks5WarmupHandler handler = new Socks5WarmupHandler(serverEndpoint.getConnectEndpoint(), serverEndpoint.getUsername(),
                    serverEndpoint.getPassword(), config.getConnectTimeoutMillis());
            try {
                ChannelFuture connectFuture = Sockets.bootstrap(config, serverEndpoint.getConnectEndpoint(), ch -> {
                    Sockets.addTcpClientHandler(ch, config, serverEndpoint.getInetEndpoint());
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

        void retire(Channel channel, String reason) {
            if (DiagnosticMetrics.isEnabled()) {
                DiagnosticMetrics.record("socks.tcp.warm.retire.count", 1D, "key=" + key + ",reason=" + reason);
            }
            Sockets.closeOnFlushed(channel);
        }

        @Override
        protected void dispose() {
            if (refillTask != null) {
                refillTask.cancel(true);
            }
            Channel channel;
            while ((channel = readyChannels.pollFirst()) != null) {
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
                            "key=" + key + ",result=hit");
                    recordState("borrow-hit");
                }
                return lease;
            } catch (TimeoutException e) {
                DiagnosticMetrics.record("socks.udp.lease.pool.borrow.count", 1D,
                        "key=" + key + ",result=miss");
                recordState("borrow-miss");
                return null;
            }
        }

        public void recycle(Socks5UdpLease lease) {
            borrowedLeases.updateAndGet(v -> v > 0 ? v - 1 : 0);
            delegate.recycle(lease);
            DiagnosticMetrics.record("socks.udp.lease.pool.recycle.count", 1D,
                    "key=" + key + ",result=success");
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
                    "key=" + key + ",result=discard");
            recordState("discard");
        }

        void recordState(String action) {
            DiagnosticMetrics.record("socks.udp.lease.pool.idle.count", delegate.idleSize(),
                    "key=" + key + ",action=" + action);
            DiagnosticMetrics.record("socks.udp.lease.pool.borrowed.count", borrowedLeases.get(),
                    "key=" + key + ",action=" + action);
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

    private Socks5UpstreamPoolManager() {
    }

    public Channel borrowWarmChannel(SocksTcpUpstream upstream) {
        SocksConfig config = (SocksConfig) upstream.getConfig();
        if (config == null || !config.isTcpWarmPoolEnabled()) {
            return null;
        }
        TcpWarmPoolKey key = upstream.warmPoolKey();
        TcpWarmPool pool = tcpPools.computeIfAbsent(key, k -> {
            TcpWarmPool p = new TcpWarmPool(k, config, upstream.getServerEndpoint());
            p.start();
            return p;
        });
        return pool.borrow();
    }

    public UdpLeasePool udpPool(SocksUdpUpstream upstream) {
        SocksConfig config = (SocksConfig) upstream.getConfig();
        if (config == null || !config.isUdpLeasePoolEnabled()) {
            return null;
        }
        long idleHint = upstream.resolveRelayIdleHintMillis();
        if (idleHint > 0 && idleHint < 1000L) {
            if (DiagnosticMetrics.isEnabled()) {
                DiagnosticMetrics.record("socks.udp.lease.disabled.count", 1D, "key=" + upstream.poolKey() + ",reason=remote-idle");
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
            DiagnosticMetrics.record("socks.udp.lease.rpc.fail.count", 1D, "key=" + key + ",phase=" + phase + ",count=" + count);
        }
        if (count >= config.getUdpLeaseRpcBreakerThreshold()) {
            breakerCache.put(key, Boolean.TRUE,
                    CachePolicy.absolute(config.getUdpLeaseRpcBreakerOpenSeconds()));
            failures.set(0);
            if (DiagnosticMetrics.isEnabled()) {
                DiagnosticMetrics.record("socks.udp.lease.breaker.open.count", 1D, "key=" + key);
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
        for (UdpLeasePool pool : udpPools.values()) {
            pool.close();
        }
        udpPools.clear();
        udpRpcFailures.clear();
    }
}
