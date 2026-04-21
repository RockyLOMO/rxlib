package org.rx.diagnostic;

import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.util.concurrent.SingleThreadEventExecutor;
import org.rx.net.SocketConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public final class DiagnosticNetMetrics {
    public static final String TRANSPORT_SERVER = "transport.server";
    public static final String TRANSPORT_CLIENT = "transport.client";
    public static final String HTTP_SERVER = "http.server";
    public static final String HTTP_CLIENT = "http.client";
    public static final String SOCKS_SERVER = "socks.server";
    public static final String SOCKS_CLIENT = "socks.client";
    public static final String RPC_SERVER = "rpc.server";
    public static final String RPC_CLIENT = "rpc.client";

    private static final ConcurrentMap<String, ComponentStats> STATS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, ChannelState> CHANNELS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<SocketConfig, String> CONFIG_COMPONENTS = new ConcurrentHashMap<>();

    private DiagnosticNetMetrics() {
    }

    public static void setComponent(SocketConfig config, String component) {
        if (config != null && component != null) {
            CONFIG_COMPONENTS.put(config, component);
        }
    }

    public static String component(SocketConfig config, String fallback) {
        String component = config == null ? null : CONFIG_COMPONENTS.get(config);
        if (component != null) {
            return component;
        }
        return fallback == null ? "net" : fallback;
    }

    static void register(Channel channel, String component) {
        if (channel == null) {
            return;
        }
        String name = normalize(component);
        ChannelState state = new ChannelState(channel, name);
        if (CHANNELS.putIfAbsent(channel.id().asShortText(), state) == null) {
            ComponentStats stats = stats(name);
            stats.active.increment();
            stats.openTotal.increment();
        }
    }

    static void unregister(Channel channel) {
        if (channel == null) {
            return;
        }
        ChannelState state = CHANNELS.remove(channel.id().asShortText());
        if (state != null) {
            ComponentStats stats = stats(state.component);
            stats.active.decrement();
            stats.closeTotal.increment();
        }
    }

    static void recordInbound(String component, long bytes) {
        if (bytes > 0L) {
            stats(normalize(component)).inboundBytes.add(bytes);
        }
    }

    static void recordOutbound(String component, long bytes) {
        if (bytes > 0L) {
            stats(normalize(component)).outboundBytes.add(bytes);
        }
    }

    public static List<DiagnosticMetric> snapshot(long timestampMillis) {
        if (STATS.isEmpty() && CHANNELS.isEmpty()) {
            return Collections.emptyList();
        }
        Map<EventLoop, Boolean> seenLoops = new IdentityHashMap<>();
        List<DiagnosticMetric> metrics = new ArrayList<>(STATS.size() * 10);
        for (Map.Entry<String, ComponentStats> entry : STATS.entrySet()) {
            String component = entry.getKey();
            ComponentStats stats = entry.getValue();
            Snapshot snapshot = snapshotChannels(component, seenLoops);
            String tags = "component=" + component;
            add(metrics, timestampMillis, "net.connection.active.count", stats.active.sum(), tags);
            add(metrics, timestampMillis, "net.connection.open.total.count", stats.openTotal.sum(), tags);
            add(metrics, timestampMillis, "net.connection.close.total.count", stats.closeTotal.sum(), tags);
            add(metrics, timestampMillis, "net.io.inbound.bytes", delta(stats.inboundBytes, stats.lastInboundBytes), tags);
            add(metrics, timestampMillis, "net.io.outbound.bytes", delta(stats.outboundBytes, stats.lastOutboundBytes), tags);
            add(metrics, timestampMillis, "net.io.inbound.total.bytes", stats.inboundBytes.sum(), tags);
            add(metrics, timestampMillis, "net.io.outbound.total.bytes", stats.outboundBytes.sum(), tags);
            add(metrics, timestampMillis, "net.write.pending.bytes", snapshot.pendingWriteBytes, tags);
            add(metrics, timestampMillis, "net.write.unwritable.count", snapshot.unwritableChannels, tags);
            add(metrics, timestampMillis, "net.eventLoop.pending.count", snapshot.pendingTasks, tags);
            add(metrics, timestampMillis, "net.eventLoop.pending.max.count", snapshot.maxPendingTasks, tags);
            seenLoops.clear();
        }
        return metrics;
    }

    private static Snapshot snapshotChannels(String component, Map<EventLoop, Boolean> seenLoops) {
        Snapshot snapshot = new Snapshot();
        for (ChannelState state : CHANNELS.values()) {
            Channel channel = state.channel;
            if (!channel.isOpen()) {
                unregister(channel);
                continue;
            }
            if (!component.equals(state.component)) {
                continue;
            }
            long pending = pendingWriteBytes(channel);
            snapshot.pendingWriteBytes += pending;
            if (!channel.isWritable()) {
                snapshot.unwritableChannels++;
            }
            EventLoop eventLoop = channel.eventLoop();
            if (eventLoop != null && !seenLoops.containsKey(eventLoop)) {
                seenLoops.put(eventLoop, Boolean.TRUE);
                int pendingTasks = pendingTasks(eventLoop);
                snapshot.pendingTasks += pendingTasks;
                snapshot.maxPendingTasks = Math.max(snapshot.maxPendingTasks, pendingTasks);
            }
        }
        return snapshot;
    }

    private static long pendingWriteBytes(Channel channel) {
        try {
            WriteBufferWaterMark waterMark = channel.config().getWriteBufferWaterMark();
            int high = waterMark.high();
            int low = waterMark.low();
            if (channel.isWritable()) {
                long beforeUnwritable = channel.bytesBeforeUnwritable();
                if (beforeUnwritable == Long.MAX_VALUE) {
                    return 0L;
                }
                return Math.max(0L, (long) high - beforeUnwritable);
            }
            long beforeWritable = channel.bytesBeforeWritable();
            if (beforeWritable == Long.MAX_VALUE) {
                return high;
            }
            return Math.max((long) high, (long) low + beforeWritable);
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static int pendingTasks(EventLoop eventLoop) {
        if (eventLoop instanceof SingleThreadEventExecutor) {
            return ((SingleThreadEventExecutor) eventLoop).pendingTasks();
        }
        return 0;
    }

    private static long delta(LongAdder adder, AtomicLong lastHolder) {
        long current = adder.sum();
        long last = lastHolder.getAndSet(current);
        return Math.max(0L, current - last);
    }

    private static ComponentStats stats(String component) {
        ComponentStats stats = STATS.get(component);
        if (stats != null) {
            return stats;
        }
        ComponentStats created = new ComponentStats();
        ComponentStats old = STATS.putIfAbsent(component, created);
        return old == null ? created : old;
    }

    private static String normalize(String component) {
        return component == null || component.length() == 0 ? "net" : component;
    }

    private static void add(List<DiagnosticMetric> metrics, long now, String name, double value, String tags) {
        if (!Double.isNaN(value) && !Double.isInfinite(value)) {
            metrics.add(new DiagnosticMetric(now, name, value, tags, null));
        }
    }

    private static final class ComponentStats {
        final LongAdder active = new LongAdder();
        final LongAdder openTotal = new LongAdder();
        final LongAdder closeTotal = new LongAdder();
        final LongAdder inboundBytes = new LongAdder();
        final LongAdder outboundBytes = new LongAdder();
        final AtomicLong lastInboundBytes = new AtomicLong();
        final AtomicLong lastOutboundBytes = new AtomicLong();
    }

    private static final class ChannelState {
        final Channel channel;
        final String component;

        ChannelState(Channel channel, String component) {
            this.channel = channel;
            this.component = component;
        }
    }

    private static final class Snapshot {
        long pendingWriteBytes;
        long unwritableChannels;
        long pendingTasks;
        long maxPendingTasks;
    }
}
