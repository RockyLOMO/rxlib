package org.rx.net.transport.hybrid;

import java.io.Serializable;

public final class HybridUdpProbeAck implements Serializable {
    private static final long serialVersionUID = 9071666974127672719L;

    public long sessionId;
    public long token;
    public String peerId;
    public int probeId;
    public long timestampNanos;

    public HybridUdpProbeAck() {
    }

    HybridUdpProbeAck(long sessionId, long token, String peerId, int probeId, long timestampNanos) {
        this.sessionId = sessionId;
        this.token = token;
        this.peerId = peerId;
        this.probeId = probeId;
        this.timestampNanos = timestampNanos;
    }
}
