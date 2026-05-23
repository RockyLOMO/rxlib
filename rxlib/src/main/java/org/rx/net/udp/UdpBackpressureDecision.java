package org.rx.net.udp;

import org.rx.net.Sockets;

public final class UdpBackpressureDecision {
    public static final UdpBackpressureDecision ALLOW_UNTRACKED =
            new UdpBackpressureDecision(true, false, Sockets.UdpWriteResult.ACCEPTED, null, 0, 0, 0, 0);
    public static final UdpBackpressureDecision ALLOW_TRACKED =
            new UdpBackpressureDecision(true, true, Sockets.UdpWriteResult.ACCEPTED, null, 0, 0, 0, 0);

    public final boolean accepted;
    public final boolean tracked;
    public final Sockets.UdpWriteResult result;
    public final String reason;
    public final int queuedBytes;
    public final int limitBytes;
    public final int queuedPackets;
    public final int limitPackets;

    private UdpBackpressureDecision(boolean accepted, boolean tracked, Sockets.UdpWriteResult result,
                                    String reason, int queuedBytes, int limitBytes,
                                    int queuedPackets, int limitPackets) {
        this.accepted = accepted;
        this.tracked = tracked;
        this.result = result;
        this.reason = reason;
        this.queuedBytes = queuedBytes;
        this.limitBytes = limitBytes;
        this.queuedPackets = queuedPackets;
        this.limitPackets = limitPackets;
    }

    public static UdpBackpressureDecision bytesOverLimit(String reason, int queuedBytes, int limitBytes,
                                                         int queuedPackets, int limitPackets) {
        return new UdpBackpressureDecision(false, false, Sockets.UdpWriteResult.PENDING_OVERLIMIT,
                reason, queuedBytes, limitBytes, queuedPackets, limitPackets);
    }

    public static UdpBackpressureDecision packetsOverLimit(String reason, int queuedPackets, int limitPackets) {
        return new UdpBackpressureDecision(false, false, Sockets.UdpWriteResult.PENDING_PACKETS_OVERLIMIT,
                reason, 0, 0, queuedPackets, limitPackets);
    }

    public static UdpBackpressureDecision unwritable(String reason, int queuedBytes, int limitBytes,
                                                     int queuedPackets, int limitPackets) {
        return new UdpBackpressureDecision(false, false, Sockets.UdpWriteResult.CHANNEL_UNWRITABLE,
                reason, queuedBytes, limitBytes, queuedPackets, limitPackets);
    }
}
