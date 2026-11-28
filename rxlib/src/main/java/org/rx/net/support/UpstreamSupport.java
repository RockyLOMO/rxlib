package org.rx.net.support;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.rx.diagnostic.DiagnosticMetrics;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.socks.SocksRpcContract;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Getter
@Setter
@ToString
public class UpstreamSupport {
    public enum Capability {
        TCP,
        UDP,
        ADAPTIVE_UDP,
        RPC
    }

    private static final class CapabilityHealth {
        final AtomicInteger consecutiveFailures = new AtomicInteger();
        volatile long degradedUntilNanos;
    }

    public static final String POOL_GENERATION = "rssRouteGeneration";
    public static final int DEFAULT_PASSIVE_FAILURE_THRESHOLD = 3;
    public static final long DEFAULT_PASSIVE_COOLDOWN_MILLIS = 15_000L;
    private String routeId;
    private boolean standardSocks5;
    private org.rx.net.EgressCodec egressCodec = org.rx.net.EgressCodec.NONE;
    private org.rx.net.EgressConfig egressConfig = org.rx.net.EgressConfig.none();

    public void setEgressCodec(org.rx.net.EgressCodec codec) {
        org.rx.net.EgressCodec eff = codec == null ? org.rx.net.EgressCodec.NONE : codec;
        this.egressCodec = eff;
        this.egressConfig = eff == org.rx.net.EgressCodec.NONE
                ? org.rx.net.EgressConfig.none() : org.rx.net.EgressConfig.client(eff);
    }

    public void setEgressConfig(org.rx.net.EgressConfig cfg) {
        this.egressConfig = cfg == null ? org.rx.net.EgressConfig.none() : cfg.copy();
        this.egressCodec = this.egressConfig.getTcpCodec();
    }
    private AuthenticEndpoint endpoint;
    private SocksRpcContract facade;
    private AuthenticEndpoint tcpClient;
    private InetSocketAddress udpClient;
    private InetSocketAddress adaptiveUdpClient;
    private boolean udp2raw;
    private int configuredWeight;
    private volatile boolean healthy = true;
    private volatile boolean closed;
    private volatile int healthFailureCount;

    public void markClosed() {
        closed = true;
    }
    private UpstreamSupport connectionTracker;
    private final AtomicInteger activeConnections = new AtomicInteger();
    private final CapabilityHealth[] capabilityHealth = new CapabilityHealth[]{
            new CapabilityHealth(),
            new CapabilityHealth(),
            new CapabilityHealth(),
            new CapabilityHealth()
    };

    public UpstreamSupport(AuthenticEndpoint endpoint, SocksRpcContract facade) {
        this.endpoint = endpoint;
        this.facade = facade;
    }

    public void recordSuccess(Capability capability) {
        if (capability == null) {
            return;
        }
        CapabilityHealth state = capabilityHealth[capability.ordinal()];
        state.consecutiveFailures.set(0);
        state.degradedUntilNanos = 0L;
    }

    public void recordFailure(Capability capability) {
        recordFailure(capability, DEFAULT_PASSIVE_FAILURE_THRESHOLD, DEFAULT_PASSIVE_COOLDOWN_MILLIS);
    }

    public void recordFailure(Capability capability, int threshold, long cooldownMillis) {
        if (capability == null) {
            return;
        }
        int effectiveThreshold = Math.max(1, threshold);
        long effectiveCooldown = Math.max(1L, cooldownMillis);
        CapabilityHealth state = capabilityHealth[capability.ordinal()];
        int failures = state.consecutiveFailures.incrementAndGet();
        if (failures >= effectiveThreshold) {
            state.degradedUntilNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(effectiveCooldown);
            DiagnosticMetrics.record("rss.upstream.capability.degraded", 1D,
                    "route=" + (routeId == null ? String.valueOf(endpoint) : routeId) + ",cap=" + capability.name());
        }
    }

    public boolean isCapabilityDegraded(Capability capability) {
        if (capability == null) {
            return false;
        }
        long until = capabilityHealth[capability.ordinal()].degradedUntilNanos;
        return until != 0L && System.nanoTime() - until < 0L;
    }

    public boolean isCapabilityHealthy(Capability capability) {
        if (capability == null) {
            return healthy;
        }
        if (capability == Capability.ADAPTIVE_UDP) {
            return !isCapabilityDegraded(Capability.ADAPTIVE_UDP);
        }
        if (!healthy) {
            return false;
        }
        return !isCapabilityDegraded(capability);
    }

    public int capabilityFailureCount(Capability capability) {
        return capability == null ? 0 : capabilityHealth[capability.ordinal()].consecutiveFailures.get();
    }

    public void resetCapabilityHealth(Capability capability) {
        recordSuccess(capability);
    }

    public int retainConnection() {
        UpstreamSupport tracker = connectionTracker;
        if (tracker != null) {
            return tracker.retainConnection();
        }
        return changeActiveConnections(1);
    }

    public int releaseConnection() {
        UpstreamSupport tracker = connectionTracker;
        if (tracker != null) {
            return tracker.releaseConnection();
        }
        return changeActiveConnections(-1);
    }

    private int changeActiveConnections(int delta) {
        int count = delta > 0 ? activeConnections.incrementAndGet() : activeConnections.decrementAndGet();
        if (count < 0) {
            activeConnections.compareAndSet(count, 0);
            count = 0;
        }
        DiagnosticMetrics.record("rss.upstream.active.connections", count, "endpoint=" + endpoint);
        return count;
    }

    public int activeConnectionCount() {
        UpstreamSupport tracker = connectionTracker;
        if (tracker != null) {
            return tracker.activeConnectionCount();
        }
        return activeConnections.get();
    }
}
