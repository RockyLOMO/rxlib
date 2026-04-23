package org.rx.net.socks;

import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentHashMap;

/**
 * UDP 压缩收益统计与旁路状态。
 * <p>
 * 初版采用轻量连续低收益计数，避免在明显不可压缩链路上持续做无效尝试。
 */
public class UdpCompressStats {
    private static final long ENTRY_EXPIRE_NANOS = 10L * 60 * 1_000_000_000L;
    private static final int CLEANUP_INTERVAL = 1024;
    private static final int LOW_GAIN_STREAK_THRESHOLD = 8;

    static final class RouteState {
        volatile long bypassUntilNanos;
        volatile long lastAccessNanos = System.nanoTime();
        int lowGainStreak;
    }

    private final boolean adaptiveBypass;
    private final long bypassWindowNanos;
    private final ConcurrentHashMap<InetSocketAddress, RouteState> routeStates = new ConcurrentHashMap<>();
    private int cleanupCounter;

    public UdpCompressStats(UdpCompressConfig config) {
        this(config != null && config.isAdaptiveBypass(),
                config != null ? config.getAdaptiveBypassWindowSeconds() : 30);
    }

    public UdpCompressStats(boolean adaptiveBypass, int bypassWindowSeconds) {
        this.adaptiveBypass = adaptiveBypass;
        this.bypassWindowNanos = Math.max(1L, bypassWindowSeconds) * 1_000_000_000L;
    }

    public boolean shouldBypass(InetSocketAddress recipient) {
        if (!adaptiveBypass || recipient == null) {
            return false;
        }
        RouteState state = routeStates.get(UdpRelayAttributes.normalize(recipient));
        if (state == null) {
            return false;
        }
        long now = System.nanoTime();
        state.lastAccessNanos = now;
        if (++cleanupCounter >= CLEANUP_INTERVAL) {
            cleanupCounter = 0;
            cleanupStaleEntries(now);
        }
        return state.bypassUntilNanos > now;
    }

    public void recordApplied(InetSocketAddress recipient) {
        if (!adaptiveBypass || recipient == null) {
            return;
        }
        RouteState state = routeStates.computeIfAbsent(UdpRelayAttributes.normalize(recipient), k -> new RouteState());
        state.lastAccessNanos = System.nanoTime();
        state.lowGainStreak = 0;
        state.bypassUntilNanos = 0L;
    }

    public void recordLowGain(InetSocketAddress recipient) {
        if (!adaptiveBypass || recipient == null) {
            return;
        }
        RouteState state = routeStates.computeIfAbsent(UdpRelayAttributes.normalize(recipient), k -> new RouteState());
        long now = System.nanoTime();
        state.lastAccessNanos = now;
        if (state.bypassUntilNanos > now) {
            return;
        }
        if (++state.lowGainStreak >= LOW_GAIN_STREAK_THRESHOLD) {
            state.lowGainStreak = 0;
            state.bypassUntilNanos = now + bypassWindowNanos;
        }
    }

    private void cleanupStaleEntries(long now) {
        routeStates.entrySet().removeIf(entry -> (now - entry.getValue().lastAccessNanos) > ENTRY_EXPIRE_NANOS);
    }
}
