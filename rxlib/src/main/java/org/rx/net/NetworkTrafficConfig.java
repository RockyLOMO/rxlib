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
    private long uploadKilobytesPerSecond;
    private long downloadKilobytesPerSecond;
    private long checkIntervalMillis = 100L;
    private long maxDelayMillis = 200L;
    private boolean tcpBackpressureEnabled = true;
    private boolean udpBackpressureEnabled = true;
    private int udpMaxPendingBytes;
    private int udpMaxPendingPackets;

    public NetworkTrafficConfig() {
    }

    public NetworkTrafficConfig(NetworkTrafficConfig source) {
        if (source != null) {
            enabled = source.enabled;
            uploadKilobytesPerSecond = source.uploadKilobytesPerSecond;
            downloadKilobytesPerSecond = source.downloadKilobytesPerSecond;
            checkIntervalMillis = source.checkIntervalMillis;
            maxDelayMillis = source.maxDelayMillis;
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
        return enabled && (uploadKilobytesPerSecond > 0L || downloadKilobytesPerSecond > 0L);
    }

    public boolean isUdpPendingLimitConfigured() {
        return udpBackpressureEnabled && (udpMaxPendingBytes > 0 || udpMaxPendingPackets > 0);
    }

    long uploadBytesPerSecond() {
        return kilobytesToBytes(uploadKilobytesPerSecond);
    }

    long downloadBytesPerSecond() {
        return kilobytesToBytes(downloadKilobytesPerSecond);
    }

    public void normalize() {
        uploadKilobytesPerSecond = Math.max(0L, uploadKilobytesPerSecond);
        downloadKilobytesPerSecond = Math.max(0L, downloadKilobytesPerSecond);
        checkIntervalMillis = checkIntervalMillis > 0L ? checkIntervalMillis : 100L;
        maxDelayMillis = maxDelayMillis > 0L ? maxDelayMillis : 200L;
        udpMaxPendingBytes = Math.max(0, udpMaxPendingBytes);
        udpMaxPendingPackets = Math.max(0, udpMaxPendingPackets);
    }

    private static long kilobytesToBytes(long kilobytes) {
        if (kilobytes <= 0L) {
            return 0L;
        }
        long maxKilobytes = Long.MAX_VALUE / 1024L;
        return kilobytes > maxKilobytes ? Long.MAX_VALUE : kilobytes * 1024L;
    }
}
