package org.rx.net.transport.hybrid;

import java.io.Serializable;

public final class HybridUdpData implements Serializable {
    private static final long serialVersionUID = -223299179669956629L;

    public long sessionId;
    public long seq;
    public long token;
    public int flags;
    public Object packet;

    public HybridUdpData() {
    }

    HybridUdpData(long sessionId, long seq, long token, int flags, Object packet) {
        this.sessionId = sessionId;
        this.seq = seq;
        this.token = token;
        this.flags = flags;
        this.packet = packet;
    }
}
