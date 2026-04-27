package org.rx.net.transport.hybrid;

import java.io.Serializable;

public final class HybridHello implements Serializable {
    private static final long serialVersionUID = 6757010367198927660L;

    public int version;
    public long sessionId;
    public String peerId;
    public String udpLocalHost;
    public int udpLocalPort;
    public long udpToken;
    public int udpSmallPacketThresholdBytes;
    public int udpProbeTimeoutMillis;
    public int udpFragmentPayloadBytes;
    public int udpMaxFragmentCount;
    public boolean enableUdpDirect;
    public boolean enableUdpHolePunch;

    public HybridHello() {
    }
}
