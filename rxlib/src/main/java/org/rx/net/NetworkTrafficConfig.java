package org.rx.net;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

@Getter
@Setter
@ToString
public class NetworkTrafficConfig implements Serializable {
    private static final long serialVersionUID = 5615815740454781434L;

    private boolean enabled;
    private long uploadBytesPerSecond;
    private long downloadBytesPerSecond;
    private long checkIntervalMillis = 1000L;
    private boolean tcpBackpressureEnabled = true;
    private boolean udpBackpressureEnabled = true;
    private int udpMaxPendingBytes;
    private int udpMaxPendingPackets;

    public NetworkTrafficConfig() {
    }

    public NetworkTrafficConfig(NetworkTrafficConfig source) {
        if (source != null) {
            enabled = source.enabled;
            uploadBytesPerSecond = source.uploadBytesPerSecond;
            downloadBytesPerSecond = source.downloadBytesPerSecond;
            checkIntervalMillis = source.checkIntervalMillis;
            tcpBackpressureEnabled = source.tcpBackpressureEnabled;
            udpBackpressureEnabled = source.udpBackpressureEnabled;
            udpMaxPendingBytes = source.udpMaxPendingBytes;
            udpMaxPendingPackets = source.udpMaxPendingPackets;
        }
        normalize();
    }

    public static NetworkTrafficConfig disabled() {
        return new NetworkTrafficConfig();
    }

    public boolean isTrafficShapingEnabled() {
        return enabled && (uploadBytesPerSecond > 0L || downloadBytesPerSecond > 0L);
    }

    public boolean isUdpPendingLimitConfigured() {
        return udpBackpressureEnabled && (udpMaxPendingBytes > 0 || udpMaxPendingPackets > 0);
    }

    public void normalize() {
        uploadBytesPerSecond = Math.max(0L, uploadBytesPerSecond);
        downloadBytesPerSecond = Math.max(0L, downloadBytesPerSecond);
        checkIntervalMillis = checkIntervalMillis > 0L ? checkIntervalMillis : 1000L;
        udpMaxPendingBytes = Math.max(0, udpMaxPendingBytes);
        udpMaxPendingPackets = Math.max(0, udpMaxPendingPackets);
    }
}
