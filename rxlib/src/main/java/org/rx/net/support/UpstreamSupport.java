package org.rx.net.support;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.rx.diagnostic.DiagnosticMetrics;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.socks.SocksRpcContract;

import java.util.concurrent.atomic.AtomicInteger;

@Getter
@Setter
@ToString
public class UpstreamSupport {
    private AuthenticEndpoint endpoint;
    private SocksRpcContract facade;
    private AuthenticEndpoint tcpClient;
    private int configuredWeight;
    private volatile boolean healthy = true;
    private volatile int healthFailureCount;
    private UpstreamSupport connectionTracker;
    private final AtomicInteger activeConnections = new AtomicInteger();

    public UpstreamSupport(AuthenticEndpoint endpoint, SocksRpcContract facade) {
        this.endpoint = endpoint;
        this.facade = facade;
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
