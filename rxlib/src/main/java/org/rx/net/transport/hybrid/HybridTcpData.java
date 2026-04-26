package org.rx.net.transport.hybrid;

import java.io.Serializable;

public final class HybridTcpData implements Serializable {
    private static final long serialVersionUID = 3285024842370890628L;

    public long sessionId;
    public long seq;
    public int flags;
    public Object packet;

    public HybridTcpData() {
    }

    HybridTcpData(long sessionId, long seq, int flags, Object packet) {
        this.sessionId = sessionId;
        this.seq = seq;
        this.flags = flags;
        this.packet = packet;
    }
}
