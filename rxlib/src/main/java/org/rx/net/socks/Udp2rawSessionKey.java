package org.rx.net.socks;

import java.io.Serializable;

public final class Udp2rawSessionKey implements Serializable {
    private static final long serialVersionUID = 1L;

    private final long sessionHi;
    private final long sessionLo;
    private final long connId;
    private final int hash;

    public Udp2rawSessionKey(long sessionHi, long sessionLo, long connId) {
        this.sessionHi = sessionHi;
        this.sessionLo = sessionLo;
        this.connId = connId;
        int h = (int) (sessionHi ^ (sessionHi >>> 32));
        h = 31 * h + (int) (sessionLo ^ (sessionLo >>> 32));
        h = 31 * h + (int) (connId ^ (connId >>> 32));
        this.hash = h;
    }

    public long getSessionHi() {
        return sessionHi;
    }

    public long getSessionLo() {
        return sessionLo;
    }

    public long getConnId() {
        return connId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Udp2rawSessionKey)) {
            return false;
        }
        Udp2rawSessionKey that = (Udp2rawSessionKey) o;
        return sessionHi == that.sessionHi && sessionLo == that.sessionLo && connId == that.connId;
    }

    @Override
    public int hashCode() {
        return hash;
    }
}
