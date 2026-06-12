package org.rx.net.udp;

import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;
import org.rx.net.NetworkFlowControl;
import org.rx.net.NetworkFlowDiagnostics;
import org.rx.net.SocketConfig;
import org.rx.net.Sockets;

import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public final class UdpBackpressurePolicy {
    private final NetworkFlowControl owner;

    public UdpBackpressurePolicy(NetworkFlowControl owner) {
        this.owner = owner;
    }

    public boolean isEnabled() {
        return owner.config().isUdpBackpressureEnabled();
    }

    public boolean shouldInstallFinalGuard(SocketConfig config, boolean forceBackpressure) {
        return (config != null && config.getUdpMtu() > 0)
                || forceBackpressure
                || owner.config().isUdpPendingLimitConfigured();
    }

    public boolean hasConfiguredPendingLimit() {
        return owner.config().isUdpPendingLimitConfigured();
    }

    public int writeLimitBytes(Channel channel) {
        Integer override = channel.attr(Sockets.ATTR_UDP_WRITE_LIMIT_BYTES).get();
        int limit = override != null && override > 0 ? override : 0;
        if (limit <= 0) {
            SocketConfig config = channel.attr(SocketConfig.ATTR_CONF).get();
            if (config != null && config.getUdpWriteLimitBytes() > 0) {
                limit = config.getUdpWriteLimitBytes();
            }
        }
        if (limit <= 0) {
            limit = Sockets.udpWriteLimitBytesByWatermark(channel);
        }

        int globalLimit = owner.config().getUdpMaxPendingBytes();
        return globalLimit > 0 ? Math.min(limit, globalLimit) : limit;
    }

    public int writeLimitPackets(Channel channel) {
        Integer override = channel.attr(Sockets.ATTR_UDP_WRITE_LIMIT_PACKETS).get();
        if (override != null && override > 0) {
            return override;
        }
        return owner.config().getUdpMaxPendingPackets();
    }

    public UdpBackpressureDecision reserve(Channel channel, int bytes,
                                           AtomicInteger pendingBytes, AtomicInteger pendingPackets,
                                           int limitBytes, int limitPackets, String reasonPrefix) {
        if (!isEnabled()) {
            return UdpBackpressureDecision.ALLOW_UNTRACKED;
        }

        int queuedPackets = 0;
        if (limitPackets > 0) {
            queuedPackets = pendingPackets.incrementAndGet();
            if (queuedPackets > limitPackets) {
                pendingPackets.addAndGet(-1);
                logDrop(channel, reasonPrefix + "pending-packets-overlimit", bytes,
                        pendingBytes == null ? 0 : Math.max(0, pendingBytes.get()), limitBytes,
                        queuedPackets, limitPackets);
                return UdpBackpressureDecision.packetsOverLimit(reasonPrefix + "pending-packets-overlimit",
                        queuedPackets, limitPackets);
            }
        }

        int queuedBytes = pendingBytes.addAndGet(bytes);
        if (queuedBytes > limitBytes) {
            pendingBytes.addAndGet(-bytes);
            if (limitPackets > 0) {
                pendingPackets.addAndGet(-1);
            }
            logDrop(channel, reasonPrefix + "pending-overlimit", bytes, queuedBytes, limitBytes,
                    queuedPackets, limitPackets);
            return UdpBackpressureDecision.bytesOverLimit(reasonPrefix + "pending-overlimit",
                    queuedBytes, limitBytes, queuedPackets, limitPackets);
        }
        if (!channel.isWritable()) {
            pendingBytes.addAndGet(-bytes);
            if (limitPackets > 0) {
                pendingPackets.addAndGet(-1);
            }
            logDrop(channel, reasonPrefix + "not-writable", bytes, queuedBytes, limitBytes,
                    queuedPackets, limitPackets);
            return UdpBackpressureDecision.unwritable(reasonPrefix + "not-writable",
                    queuedBytes, limitBytes, queuedPackets, limitPackets);
        }
        return UdpBackpressureDecision.ALLOW_TRACKED;
    }

    public void release(int bytes, AtomicInteger pendingBytes, AtomicInteger pendingPackets, int limitPackets) {
        if (pendingBytes != null) {
            pendingBytes.addAndGet(-bytes);
        }
        if (pendingPackets != null && limitPackets > 0) {
            pendingPackets.addAndGet(-1);
        }
    }

    private void logDrop(Channel channel, String reason, int bytes,
                         int queuedBytes, int limitBytes, int queuedPackets, int limitPackets) {
        boolean flowDebug = NetworkFlowDiagnostics.isUdpDropDebugEnabled();
        if (!flowDebug && !log.isDebugEnabled()) {
            return;
        }
        if (flowDebug) {
            log.info("UDP backpressure drop reason={} channel={} bytes={} queuedBytes={} limitBytes={} queuedPackets={} limitPackets={} writable={} active={} globalUdpLimitBytes={} globalUdpLimitPackets={}",
                    reason, channel, bytes, queuedBytes, limitBytes, queuedPackets, limitPackets,
                    channel != null && channel.isWritable(), channel != null && channel.isActive(),
                    owner.config().getUdpMaxPendingBytes(), owner.config().getUdpMaxPendingPackets());
        } else {
            log.debug("UDP backpressure drop reason={} channel={} bytes={} queuedBytes={} limitBytes={} queuedPackets={} limitPackets={} writable={} active={} globalUdpLimitBytes={} globalUdpLimitPackets={}",
                    reason, channel, bytes, queuedBytes, limitBytes, queuedPackets, limitPackets,
                    channel != null && channel.isWritable(), channel != null && channel.isActive(),
                    owner.config().getUdpMaxPendingBytes(), owner.config().getUdpMaxPendingPackets());
        }
    }
}
