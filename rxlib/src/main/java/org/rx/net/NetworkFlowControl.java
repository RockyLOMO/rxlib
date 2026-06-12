package org.rx.net;

import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.local.LocalChannel;
import io.netty.handler.traffic.GlobalChannelTrafficShapingHandler;
import io.netty.util.concurrent.EventExecutor;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.RxConfig;
import org.rx.diagnostic.DiagnosticMetrics;
import org.rx.net.udp.UdpBackpressurePolicy;

@Slf4j
public final class NetworkFlowControl {
    public static final NetworkFlowControl DEFAULT = new NetworkFlowControl();
    public static final String GLOBAL_TRAFFIC_HANDLER = "GLOBAL_TRAFFIC_SHAPING";

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
        if (channel == null || channel instanceof LocalChannel) {
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

    public UdpBackpressurePolicy udpBackpressurePolicy() {
        return udpBackpressurePolicy;
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
