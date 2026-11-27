package org.rx.net;

import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.local.LocalChannel;
import io.netty.handler.traffic.GlobalChannelTrafficShapingHandler;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.EventExecutor;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.RxConfig;
import org.rx.diagnostic.DiagnosticMetrics;
import org.rx.net.udp.UdpBackpressurePolicy;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

@Slf4j
public final class NetworkFlowControl {
    public static final NetworkFlowControl DEFAULT = new NetworkFlowControl();
    public static final String GLOBAL_TRAFFIC_HANDLER = "GLOBAL_TRAFFIC_SHAPING";
    public static final String BANDWIDTH_LIMITER_HANDLER = "SCOPED_BANDWIDTH_LIMITER";
    public static final AttributeKey<BandwidthLimiter> ATTR_BANDWIDTH_LIMITER =
            AttributeKey.valueOf("netScopedBandwidthLimiter");

    private final Object trafficLock = new Object();
    private final UdpBackpressurePolicy udpBackpressurePolicy = new UdpBackpressurePolicy(this);
    private volatile NetworkTrafficConfig config = NetworkTrafficConfig.disabled();
    private volatile GlobalChannelTrafficShapingHandler globalTrafficHandler;

    private NetworkFlowControl() {
    }

    public NetworkTrafficConfig config() {
        return config;
    }

    public static void refresh() {
        DEFAULT.refresh(RxConfig.INSTANCE.getNet().getGlobalTraffic());
    }

    public void refresh(NetworkTrafficConfig source) {
        NetworkTrafficConfig snapshot = new NetworkTrafficConfig(source);
        config = snapshot;

        GlobalChannelTrafficShapingHandler handler = globalTrafficHandler;
        if (handler != null) {
            boolean enabled = snapshot.isTrafficShapingEnabled();
            handler.configure(enabled ? snapshot.uploadBytesPerSecond() : 0L,
                    enabled ? snapshot.downloadBytesPerSecond() : 0L,
                    snapshot.getCheckIntervalMillis());
            handler.setMaxTimeWait(snapshot.getMaxDelayMillis());
        }
    }

    public boolean install(Channel channel) {
        if (channel == null) {
            return false;
        }
        NetworkFlowDiagnostics.register(channel);
        if (shouldSkipTrafficShaping(channel)) {
            return false;
        }

        NetworkTrafficConfig snapshot = config;
        if (!snapshot.isTrafficShapingEnabled()) {
            return false;
        }

        ChannelPipeline pipeline = channel.pipeline();
        if (pipeline.get(GLOBAL_TRAFFIC_HANDLER) != null
                || pipeline.get(GlobalChannelTrafficShapingHandler.class) != null) {
            return false;
        }
        pipeline.addLast(GLOBAL_TRAFFIC_HANDLER, globalTrafficHandler(snapshot));
        NetworkFlowDiagnostics.register(channel);
        DiagnosticMetrics.record("net.flow.global.traffic.channel.count", 1D,
                "protocol=" + Sockets.protocolName(channel));
        return true;
    }

    static boolean shouldSkipTrafficShaping(Channel channel) {
        if (channel instanceof LocalChannel) {
            return true;
        }
        if (isLoopbackAddress(channel.attr(Sockets.ATTR_CONNECT_HINT).get())) {
            return true;
        }
        return isLoopbackAddress(channel.localAddress()) && isLoopbackAddress(channel.remoteAddress());
    }

    private static boolean isLoopbackAddress(SocketAddress address) {
        if (!(address instanceof InetSocketAddress)) {
            return false;
        }
        InetSocketAddress endpoint = (InetSocketAddress) address;
        InetAddress inetAddress = endpoint.getAddress();
        if (inetAddress != null) {
            return inetAddress.isLoopbackAddress();
        }
        String host = endpoint.getHostString();
        return "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "::1".equals(host)
                || "0:0:0:0:0:0:0:1".equals(host);
    }

    public UdpBackpressurePolicy udpBackpressurePolicy() {
        return udpBackpressurePolicy;
    }

    /**
     * Opt-in attachment of a scoped {@link BandwidthLimiter} to a specific channel.
     * Does not affect or modify global traffic shaping.
     */
    public static void attachBandwidthLimiter(Channel channel, BandwidthLimiter limiter) {
        if (channel != null) {
            channel.attr(ATTR_BANDWIDTH_LIMITER).set(limiter);
        }
    }

    public static BandwidthLimiter bandwidthLimiter(Channel channel) {
        return channel == null ? null : channel.attr(ATTR_BANDWIDTH_LIMITER).get();
    }

    /**
     * Opt-in installation of a standalone {@link BandwidthChannelHandler} on a specific channel
     * (e.g. an independent proxy listener or upstream channel). Never auto-installed globally.
     */
    public static boolean installBandwidthLimiter(Channel channel, BandwidthLimiter limiter) {
        return installBandwidthLimiter(channel, limiter,
                BandwidthLimiter.Direction.UPLOAD, BandwidthLimiter.Direction.DOWNLOAD);
    }

    public static boolean installBandwidthLimiter(Channel channel, BandwidthLimiter limiter,
                                                  BandwidthLimiter.Direction readDirection,
                                                  BandwidthLimiter.Direction writeDirection) {
        if (channel == null || limiter == null) {
            return false;
        }
        attachBandwidthLimiter(channel, limiter);
        ChannelPipeline pipeline = channel.pipeline();
        if (pipeline.get(BANDWIDTH_LIMITER_HANDLER) != null
                || pipeline.get(BandwidthChannelHandler.class) != null) {
            return false;
        }
        pipeline.addLast(BANDWIDTH_LIMITER_HANDLER,
                new BandwidthChannelHandler(limiter, readDirection, writeDirection));
        return true;
    }

    GlobalChannelTrafficShapingHandler globalTrafficHandler() {
        return globalTrafficHandler;
    }

    private GlobalChannelTrafficShapingHandler globalTrafficHandler(NetworkTrafficConfig snapshot) {
        GlobalChannelTrafficShapingHandler handler = globalTrafficHandler;
        if (handler != null) {
            return handler;
        }
        synchronized (trafficLock) {
            handler = globalTrafficHandler;
            if (handler != null) {
                return handler;
            }

            EventExecutor executor = Sockets.reactor(Sockets.ReactorNames.SHARED_TCP, true).next();
            handler = new GlobalChannelTrafficShapingHandler(executor,
                    snapshot.uploadBytesPerSecond(), snapshot.downloadBytesPerSecond(),
                    0L, 0L, snapshot.getCheckIntervalMillis(), snapshot.getMaxDelayMillis());
            globalTrafficHandler = handler;
            log.info("Network global traffic shaping enabled uploadKilobytesPerSecond={} downloadKilobytesPerSecond={} checkIntervalMillis={} maxDelayMillis={}",
                    snapshot.getUploadKilobytesPerSecond(), snapshot.getDownloadKilobytesPerSecond(),
                    snapshot.getCheckIntervalMillis(), snapshot.getMaxDelayMillis());
            return handler;
        }
    }
}
