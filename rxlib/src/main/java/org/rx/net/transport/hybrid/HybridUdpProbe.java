package org.rx.net.transport.hybrid;

import java.io.Serializable;

public final class HybridUdpProbe implements Serializable {
    private static final long serialVersionUID = 1113312359036270667L;

    public long sessionId;
    public long token;
    public String peerId;
    public int probeId;
    public long timestampNanos;

    public HybridUdpProbe() {
    }

    HybridUdpProbe(long sessionId, long token, String peerId, int probeId, long timestampNanos) {
        this.sessionId = sessionId;
        this.token = token;
        this.peerId = peerId;
        this.probeId = probeId;
        this.timestampNanos = timestampNanos;
    }
}
