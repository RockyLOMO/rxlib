package org.rx.diagnostic;

import io.netty.channel.ChannelPipeline;
import lombok.extern.slf4j.Slf4j;
import org.rx.net.SocketConfig;

/**
 * Lightweight metric bridge without stack collection.
 */
@Slf4j
public final class DiagnosticMetrics {
    public static final String NET_TRANSPORT_SERVER = DiagnosticNetMetrics.TRANSPORT_SERVER;
    public static final String NET_TRANSPORT_CLIENT = DiagnosticNetMetrics.TRANSPORT_CLIENT;
    public static final String NET_HTTP_SERVER = DiagnosticNetMetrics.HTTP_SERVER;
    public static final String NET_HTTP_CLIENT = DiagnosticNetMetrics.HTTP_CLIENT;
    public static final String NET_SOCKS_SERVER = DiagnosticNetMetrics.SOCKS_SERVER;
    public static final String NET_SOCKS_CLIENT = DiagnosticNetMetrics.SOCKS_CLIENT;
    public static final String NET_RPC_SERVER = DiagnosticNetMetrics.RPC_SERVER;
    public static final String NET_RPC_CLIENT = DiagnosticNetMetrics.RPC_CLIENT;

    private static final ThreadLocal<Boolean> RECORDING = new ThreadLocal<Boolean>();
    private static final ThreadLocal<Boolean> SUPPRESSED = new ThreadLocal<Boolean>();

    private DiagnosticMetrics() {
    }

    public static boolean isEnabled() {
        DiagnosticMonitor monitor = DiagnosticMonitor.getDefault();
        return monitor != null && monitor.isRunning()
                && !Boolean.TRUE.equals(RECORDING.get())
                && !Boolean.TRUE.equals(SUPPRESSED.get());
    }

    public static void record(String name, double value) {
        record(name, value, null);
    }

    public static void record(String name, double value, String tags) {
        record(System.currentTimeMillis(), name, value, tags, null);
    }

    public static void record(long timestampMillis, String name, double value, String tags, String incidentId) {
        DiagnosticMonitor monitor = DiagnosticMonitor.getDefault();
        if (monitor == null || !monitor.isRunning()
                || Boolean.TRUE.equals(RECORDING.get())
                || Boolean.TRUE.equals(SUPPRESSED.get())) {
            return;
        }
        RECORDING.set(true);
        try {
            monitor.getStore().recordMetric(new DiagnosticMetric(timestampMillis, name, value, tags, incidentId));
        } catch (Throwable e) {
            log.warn("diagnostic metric record failed, name={}, tags={}, incidentId={}", name, tags, incidentId, e);
            // Metrics must never affect business execution.
        } finally {
            RECORDING.remove();
        }
    }

    public static void setNetComponent(SocketConfig config, String component) {
        DiagnosticNetMetrics.setComponent(config, component);
    }

    public static String netComponent(SocketConfig config, String fallback) {
        return DiagnosticNetMetrics.component(config, fallback);
    }

    public static void installNetIoHandler(ChannelPipeline pipeline, String component) {
        if (isEnabled()) {
            try {
                DiagnosticNetIoHandler.install(pipeline, component);
            } catch (Throwable e) {
                log.warn("diagnostic net io handler install failed, component={}", component, e);
                // Metrics install failures must never affect business execution.
            }
        }
    }

    public static boolean enterSuppressed() {
        boolean old = Boolean.TRUE.equals(SUPPRESSED.get());
        SUPPRESSED.set(true);
        return old;
    }

    public static void exitSuppressed(boolean old) {
        if (old) {
            SUPPRESSED.set(true);
        } else {
            SUPPRESSED.remove();
        }
    }
}
