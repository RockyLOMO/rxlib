package org.rx.net;

import io.netty.channel.Channel;

import java.util.concurrent.atomic.AtomicInteger;

public final class UdpBackpressurePolicy {
    private final NetworkFlowControl owner;

    UdpBackpressurePolicy(NetworkFlowControl owner) {
        this.owner = owner;
    }

    public boolean isEnabled() {
        return owner.config().isUdpBackpressureEnabled();
    }

    public boolean shouldInstallFinalGuard(SocketConfig config, boolean forceBackpressure) {
        return config != null && config.getUdpMtu() > 0
                || forceBackpressure
                || owner.config().isUdpPendingLimitConfigured();
    }

    boolean hasConfiguredPendingLimit() {
        return owner.config().isUdpPendingLimitConfigured();
    }

    int writeLimitBytes(Channel channel) {
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

    int writeLimitPackets(Channel channel) {
        Integer override = channel.attr(Sockets.ATTR_UDP_WRITE_LIMIT_PACKETS).get();
        if (override != null && override > 0) {
            return override;
        }
        return owner.config().getUdpMaxPendingPackets();
    }

    UdpBackpressureDecision reserve(Channel channel, int bytes,
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
            return UdpBackpressureDecision.bytesOverLimit(reasonPrefix + "pending-overlimit",
                    queuedBytes, limitBytes, queuedPackets, limitPackets);
        }
        if (!channel.isWritable()) {
            pendingBytes.addAndGet(-bytes);
            if (limitPackets > 0) {
                pendingPackets.addAndGet(-1);
            }
            return UdpBackpressureDecision.unwritable(reasonPrefix + "not-writable",
                    queuedBytes, limitBytes, queuedPackets, limitPackets);
        }
        return UdpBackpressureDecision.ALLOW_TRACKED;
    }

    void release(int bytes, AtomicInteger pendingBytes, AtomicInteger pendingPackets, int limitPackets) {
        if (pendingBytes != null) {
            pendingBytes.addAndGet(-bytes);
        }
        if (pendingPackets != null && limitPackets > 0) {
            pendingPackets.addAndGet(-1);
        }
    }
}
