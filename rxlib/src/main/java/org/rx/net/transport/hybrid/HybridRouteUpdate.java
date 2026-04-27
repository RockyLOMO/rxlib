package org.rx.net.transport.hybrid;

import java.io.Serializable;

public final class HybridRouteUpdate implements Serializable {
    private static final long serialVersionUID = -6210966428498699370L;

    public long sessionId;
    public HybridRouteState routeState;
    public String udpRemoteHost;
    public int udpRemotePort;
    public String reason;

    public HybridRouteUpdate() {
    }
}
