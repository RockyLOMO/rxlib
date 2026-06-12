package org.rx.net;

import io.netty.channel.Channel;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelOption;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.handler.traffic.AbstractTrafficShapingHandler;
import io.netty.handler.traffic.GlobalChannelTrafficShapingHandler;
import io.netty.handler.traffic.TrafficCounter;
import io.netty.util.concurrent.DefaultThreadFactory;
import lombok.extern.slf4j.Slf4j;
import org.rx.io.Bytes;

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

@Slf4j
public final class NetworkFlowDiagnostics {
    private static final String PROP_ENABLED = "app.net.flowDebug.enabled";
    private static final String PROP_INTERVAL_MILLIS = "app.net.flowDebug.intervalMillis";
    private static final String PROP_TOP_CHANNELS = "app.net.flowDebug.topChannels";
    private static final String PROP_UDP_DROPS = "app.net.flowDebug.udpDrops";
    private static final int DEFAULT_INTERVAL_MILLIS = 1000;
    private static final int MIN_INTERVAL_MILLIS = 200;
    private static final int DEFAULT_TOP_CHANNELS = 5;

    static final NetworkFlowDiagnostics INSTANCE = new NetworkFlowDiagnostics();

    private final boolean enabled = readBoolean(PROP_ENABLED, false);
    private final boolean udpDropDebugEnabled = readBoolean(PROP_UDP_DROPS, false);
    private final int intervalMillis = Math.max(MIN_INTERVAL_MILLIS,
            readInt(PROP_INTERVAL_MILLIS, DEFAULT_INTERVAL_MILLIS));
    private final int topChannels = Math.max(0, readInt(PROP_TOP_CHANNELS, DEFAULT_TOP_CHANNELS));
    private final Set<Channel> channels = Collections.newSetFromMap(new ConcurrentHashMap<Channel, Boolean>());
    private final ConcurrentHashMap<Channel, ChannelTrafficState> trafficStates =
            new ConcurrentHashMap<Channel, ChannelTrafficState>();
    private final AtomicBoolean started = new AtomicBoolean();
    private final LongAdder tcpBackpressureStarts = new LongAdder();
    private final LongAdder tcpBackpressureEnds = new LongAdder();
    private final LongAdder tcpBackpressureTimeouts = new LongAdder();
    private final LongAdder tcpBackpressurePauseMillis = new LongAdder();
    private volatile ScheduledExecutorService executor;

    private NetworkFlowDiagnostics() {
    }

    static boolean isEnabled() {
        return INSTANCE.enabled;
    }

    public static boolean isUdpDropDebugEnabled() {
        return INSTANCE.udpDropDebugEnabled;
    }

    static void register(Channel channel) {
        INSTANCE.register0(channel);
    }

    static void recordTcpBackpressureTimeout() {
        if (INSTANCE.enabled) {
            INSTANCE.tcpBackpressureTimeouts.increment();
        }
    }

    static void recordTcpBackpressureStart() {
        if (INSTANCE.enabled) {
            INSTANCE.tcpBackpressureStarts.increment();
        }
    }

    static void recordTcpBackpressureEnd(long pauseMillis) {
        if (INSTANCE.enabled) {
            INSTANCE.tcpBackpressureEnds.increment();
            if (pauseMillis > 0L) {
                INSTANCE.tcpBackpressurePauseMillis.add(pauseMillis);
            }
        }
    }

    private void register0(final Channel channel) {
        if (!enabled || channel == null) {
            return;
        }
        channels.add(channel);
        ensureStarted();
        channel.closeFuture().addListener(f -> {
            channels.remove(channel);
            trafficStates.remove(channel);
        });
    }

    private void ensureStarted() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(
                new DefaultThreadFactory("net-flow-diagnostics", true, Thread.NORM_PRIORITY));
        executor.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                try {
                    report();
                } catch (Throwable e) {
                    log.warn("NET_FLOW_DEBUG report failed", e);
                }
            }
        }, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
        log.info("NET_FLOW_DEBUG enabled intervalMillis={} topChannels={}", intervalMillis, topChannels);
    }

    private void report() {
        GlobalChannelTrafficShapingHandler handler = NetworkFlowControl.DEFAULT.globalTrafficHandler();
        if (handler == null && channels.isEmpty()) {
            return;
        }

        long pendingBytes = 0L;
        long udpPendingBytes = 0L;
        long udpPendingPackets = 0L;
        long proxyReadBytes = 0L;
        long proxyWrittenBytes = 0L;
        int total = 0;
        int active = 0;
        int writable = 0;
        int autoReadOff = 0;
        int globalHandlers = 0;
        int tcpBackpressureHandlers = 0;
        int tcpBackpressurePaused = 0;
        List<ChannelSnapshot> snapshots = topChannels > 0 ? new ArrayList<ChannelSnapshot>() : null;

        Iterator<Channel> iterator = channels.iterator();
        while (iterator.hasNext()) {
            Channel channel = iterator.next();
            if (channel == null || !channel.isOpen()) {
                iterator.remove();
                continue;
            }

            total++;
            if (channel.isActive()) {
                active++;
            }
            if (channel.isWritable()) {
                writable++;
            }
            if (!channel.config().isAutoRead()) {
                autoReadOff++;
            }
            if (channel.pipeline().get(NetworkFlowControl.GLOBAL_TRAFFIC_HANDLER) != null) {
                globalHandlers++;
            }
            TcpBackpressureHandler backpressureHandler = channel.pipeline().get(TcpBackpressureHandler.class);
            if (backpressureHandler != null) {
                tcpBackpressureHandlers++;
                if (backpressureHandler.isPaused()) {
                    tcpBackpressurePaused++;
                }
            }

            long channelPendingBytes = pendingOutboundBytes(channel);
            ChannelTrafficDelta trafficDelta = channelTrafficDelta(channel);
            pendingBytes += channelPendingBytes;
            udpPendingBytes += Sockets.udpPendingWriteBytes(channel);
            udpPendingPackets += Sockets.udpPendingWritePackets(channel);
            proxyReadBytes += trafficDelta.readBytes;
            proxyWrittenBytes += trafficDelta.writtenBytes;
            if (snapshots != null && (channelPendingBytes > 0L || !channel.isWritable()
                    || !channel.config().isAutoRead() || backpressureHandler != null
                    || trafficDelta.readBytes > 0L || trafficDelta.writtenBytes > 0L)) {
                snapshots.add(new ChannelSnapshot(channel, channelPendingBytes, backpressureHandler,
                        trafficDelta.readBytes, trafficDelta.writtenBytes));
            }
        }

        long queuesSize = -1L;
        long lastReadThroughput = 0L;
        long lastWriteThroughput = 0L;
        long currentReadBytes = 0L;
        long currentWrittenBytes = 0L;
        long cumulativeReadBytes = 0L;
        long cumulativeWrittenBytes = 0L;
        long writeLimit = 0L;
        long readLimit = 0L;
        long checkInterval = 0L;
        long maxDelay = 0L;
        int channelTrafficCounters = 0;
        if (handler != null) {
            TrafficCounter counter = handler.trafficCounter();
            if (counter != null) {
                lastReadThroughput = counter.lastReadThroughput();
                lastWriteThroughput = counter.lastWriteThroughput();
                currentReadBytes = counter.currentReadBytes();
                currentWrittenBytes = counter.currentWrittenBytes();
                cumulativeReadBytes = counter.cumulativeReadBytes();
                cumulativeWrittenBytes = counter.cumulativeWrittenBytes();
            }
            queuesSize = handler.queuesSize();
            writeLimit = handler.getWriteLimit();
            readLimit = handler.getReadLimit();
            checkInterval = handler.getCheckInterval();
            maxDelay = handler.getMaxTimeWait();
            channelTrafficCounters = handler.channelTrafficCounters().size();
        }

        long starts = tcpBackpressureStarts.sumThenReset();
        long ends = tcpBackpressureEnds.sumThenReset();
        long timeouts = tcpBackpressureTimeouts.sumThenReset();
        long pauseMillis = tcpBackpressurePauseMillis.sumThenReset();
        long avgPauseMillis = ends == 0L ? 0L : pauseMillis / ends;

        StringBuilder sb = new StringBuilder(512);
        sb.append("NET_FLOW_DEBUG")
                .append(" channels=").append(total)
                .append(",active=").append(active)
                .append(",writable=").append(writable)
                .append(",autoReadOff=").append(autoReadOff)
                .append(",globalHandlers=").append(globalHandlers)
                .append(",trafficCounters=").append(channelTrafficCounters)
                .append(",outPending=").append(Bytes.readableByteSize(pendingBytes))
                .append(",globalQueue=").append(queuesSize < 0L ? "n/a" : Bytes.readableByteSize(queuesSize))
                .append(",udpPending=").append(Bytes.readableByteSize(udpPendingBytes)).append('/').append(udpPendingPackets)
                .append(",proxyReadDelta=").append(Bytes.readableByteSize(proxyReadBytes))
                .append(",proxyWriteDelta=").append(Bytes.readableByteSize(proxyWrittenBytes))
                .append(",limitRead=").append(Bytes.readableByteSize(readLimit)).append("/s")
                .append(",limitWrite=").append(Bytes.readableByteSize(writeLimit)).append("/s")
                .append(",lastRead=").append(Bytes.readableByteSize(lastReadThroughput)).append("/s")
                .append(",lastWrite=").append(Bytes.readableByteSize(lastWriteThroughput)).append("/s")
                .append(",currentRead=").append(Bytes.readableByteSize(currentReadBytes))
                .append(",currentWrite=").append(Bytes.readableByteSize(currentWrittenBytes))
                .append(",cumRead=").append(Bytes.readableByteSize(cumulativeReadBytes))
                .append(",cumWrite=").append(Bytes.readableByteSize(cumulativeWrittenBytes))
                .append(",checkMs=").append(checkInterval)
                .append(",maxDelayMs=").append(maxDelay)
                .append(",tcpBpHandlers=").append(tcpBackpressureHandlers)
                .append(",tcpBpPaused=").append(tcpBackpressurePaused)
                .append(",tcpBpDelta=start:").append(starts)
                .append(",end:").append(ends)
                .append(",timeout:").append(timeouts)
                .append(",avgPauseMs:").append(avgPauseMillis);
        appendTopChannels(sb, snapshots);
        log.info(sb.toString());
    }

    private void appendTopChannels(StringBuilder sb, List<ChannelSnapshot> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) {
            return;
        }
        Collections.sort(snapshots, new Comparator<ChannelSnapshot>() {
            @Override
            public int compare(ChannelSnapshot o1, ChannelSnapshot o2) {
                return Long.compare(o2.score(), o1.score());
            }
        });
        sb.append(",top=[");
        int count = Math.min(topChannels, snapshots.size());
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(';');
            }
            snapshots.get(i).appendTo(sb);
        }
        sb.append(']');
    }

    private static long pendingOutboundBytes(Channel channel) {
        try {
            ChannelOutboundBuffer outboundBuffer = channel.unsafe().outboundBuffer();
            return outboundBuffer == null ? 0L : Math.max(0L, outboundBuffer.totalPendingWriteBytes());
        } catch (Throwable e) {
            return 0L;
        }
    }

    private ChannelTrafficDelta channelTrafficDelta(Channel channel) {
        TrafficCounter counter = proxyTrafficCounter(channel);
        if (counter == null) {
            trafficStates.remove(channel);
            return ChannelTrafficDelta.EMPTY;
        }
        ChannelTrafficState state = trafficStates.get(channel);
        if (state == null) {
            ChannelTrafficState next = new ChannelTrafficState();
            ChannelTrafficState old = trafficStates.putIfAbsent(channel, next);
            state = old == null ? next : old;
        }

        long readBytes = counter.cumulativeReadBytes();
        long writtenBytes = counter.cumulativeWrittenBytes();
        if (!state.initialized) {
            state.initialized = true;
            state.lastReadBytes = readBytes;
            state.lastWrittenBytes = writtenBytes;
            return ChannelTrafficDelta.EMPTY;
        }

        long readDelta = Math.max(0L, readBytes - state.lastReadBytes);
        long writtenDelta = Math.max(0L, writtenBytes - state.lastWrittenBytes);
        state.lastReadBytes = readBytes;
        state.lastWrittenBytes = writtenBytes;
        return readDelta == 0L && writtenDelta == 0L
                ? ChannelTrafficDelta.EMPTY : new ChannelTrafficDelta(readDelta, writtenDelta);
    }

    private static TrafficCounter proxyTrafficCounter(Channel channel) {
        try {
            Object handler = channel.pipeline().get("ProxyManageHandler");
            if (handler instanceof AbstractTrafficShapingHandler) {
                return ((AbstractTrafficShapingHandler) handler).trafficCounter();
            }
        } catch (Throwable e) {
            return null;
        }
        return null;
    }

    private static boolean readBoolean(String key, boolean defaultValue) {
        String value = System.getProperty(key);
        if (value == null || value.length() == 0) {
            return defaultValue;
        }
        return "true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value);
    }

    private static int readInt(String key, int defaultValue) {
        String value = System.getProperty(key);
        if (value == null || value.length() == 0) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static final class ChannelTrafficState {
        boolean initialized;
        long lastReadBytes;
        long lastWrittenBytes;
    }

    private static final class ChannelTrafficDelta {
        static final ChannelTrafficDelta EMPTY = new ChannelTrafficDelta(0L, 0L);

        final long readBytes;
        final long writtenBytes;

        ChannelTrafficDelta(long readBytes, long writtenBytes) {
            this.readBytes = readBytes;
            this.writtenBytes = writtenBytes;
        }
    }

    private static final class ChannelSnapshot {
        final String id;
        final SocketAddress localAddress;
        final SocketAddress remoteAddress;
        final boolean active;
        final boolean writable;
        final boolean autoRead;
        final boolean globalTraffic;
        final boolean tcpBackpressure;
        final boolean tcpBackpressurePaused;
        final long pendingBytes;
        final long bytesBeforeWritable;
        final long bytesBeforeUnwritable;
        final int writeLowWaterMark;
        final int writeHighWaterMark;
        final long readDeltaBytes;
        final long writtenDeltaBytes;

        ChannelSnapshot(Channel channel, long pendingBytes, TcpBackpressureHandler backpressureHandler,
                        long readDeltaBytes, long writtenDeltaBytes) {
            id = channel.id().asShortText();
            localAddress = safeLocalAddress(channel);
            remoteAddress = safeRemoteAddress(channel);
            active = channel.isActive();
            writable = channel.isWritable();
            autoRead = channel.config().isAutoRead();
            globalTraffic = channel.pipeline().get(NetworkFlowControl.GLOBAL_TRAFFIC_HANDLER) != null;
            tcpBackpressure = backpressureHandler != null;
            tcpBackpressurePaused = backpressureHandler != null && backpressureHandler.isPaused();
            this.pendingBytes = pendingBytes;
            bytesBeforeWritable = safeBytesBeforeWritable(channel);
            bytesBeforeUnwritable = safeBytesBeforeUnwritable(channel);
            WriteBufferWaterMark waterMark = channel.config().getOption(ChannelOption.WRITE_BUFFER_WATER_MARK);
            writeLowWaterMark = waterMark == null ? 0 : waterMark.low();
            writeHighWaterMark = waterMark == null ? 0 : waterMark.high();
            this.readDeltaBytes = readDeltaBytes;
            this.writtenDeltaBytes = writtenDeltaBytes;
        }

        long score() {
            return Math.max(pendingBytes, Math.max(readDeltaBytes, writtenDeltaBytes));
        }

        void appendTo(StringBuilder sb) {
            sb.append(id)
                    .append("{active=").append(active)
                    .append(",writable=").append(writable)
                    .append(",autoRead=").append(autoRead)
                    .append(",pending=").append(Bytes.readableByteSize(pendingBytes))
                    .append(",rDelta=").append(Bytes.readableByteSize(readDeltaBytes))
                    .append(",wDelta=").append(Bytes.readableByteSize(writtenDeltaBytes))
                    .append(",beforeWritable=").append(Bytes.readableByteSize(bytesBeforeWritable))
                    .append(",beforeUnwritable=").append(Bytes.readableByteSize(bytesBeforeUnwritable))
                    .append(",wm=").append(writeLowWaterMark).append('/').append(writeHighWaterMark)
                    .append(",global=").append(globalTraffic)
                    .append(",bp=").append(tcpBackpressure)
                    .append(",bpPaused=").append(tcpBackpressurePaused)
                    .append(",local=").append(localAddress)
                    .append(",remote=").append(remoteAddress)
                    .append('}');
        }

        private static SocketAddress safeLocalAddress(Channel channel) {
            try {
                return channel.localAddress();
            } catch (Throwable e) {
                return null;
            }
        }

        private static SocketAddress safeRemoteAddress(Channel channel) {
            try {
                return channel.remoteAddress();
            } catch (Throwable e) {
                return null;
            }
        }

        private static long safeBytesBeforeWritable(Channel channel) {
            try {
                return Math.max(0L, channel.bytesBeforeWritable());
            } catch (Throwable e) {
                return 0L;
            }
        }

        private static long safeBytesBeforeUnwritable(Channel channel) {
            try {
                return Math.max(0L, channel.bytesBeforeUnwritable());
            } catch (Throwable e) {
                return 0L;
            }
        }
    }
}
