package org.rx.net;

final class UdpBackpressureDecision {
    static final UdpBackpressureDecision ALLOW_UNTRACKED =
            new UdpBackpressureDecision(true, false, Sockets.UdpWriteResult.ACCEPTED, null, 0, 0, 0, 0);
    static final UdpBackpressureDecision ALLOW_TRACKED =
            new UdpBackpressureDecision(true, true, Sockets.UdpWriteResult.ACCEPTED, null, 0, 0, 0, 0);

    final boolean accepted;
    final boolean tracked;
    final Sockets.UdpWriteResult result;
    final String reason;
    final int queuedBytes;
    final int limitBytes;
    final int queuedPackets;
    final int limitPackets;

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

    static UdpBackpressureDecision bytesOverLimit(String reason, int queuedBytes, int limitBytes,
                                                  int queuedPackets, int limitPackets) {
        return new UdpBackpressureDecision(false, false, Sockets.UdpWriteResult.PENDING_OVERLIMIT,
                reason, queuedBytes, limitBytes, queuedPackets, limitPackets);
    }

    static UdpBackpressureDecision packetsOverLimit(String reason, int queuedPackets, int limitPackets) {
        return new UdpBackpressureDecision(false, false, Sockets.UdpWriteResult.PENDING_PACKETS_OVERLIMIT,
                reason, 0, 0, queuedPackets, limitPackets);
    }

    static UdpBackpressureDecision unwritable(String reason, int queuedBytes, int limitBytes,
                                              int queuedPackets, int limitPackets) {
        return new UdpBackpressureDecision(false, false, Sockets.UdpWriteResult.CHANNEL_UNWRITABLE,
                reason, queuedBytes, limitBytes, queuedPackets, limitPackets);
    }
}
