package org.rx.net.transport.hybrid;

import java.io.Serializable;

public final class HybridHelloAck implements Serializable {
    private static final long serialVersionUID = -1723850043020104133L;

    public int version;
    public long sessionId;
    public String peerId;
    public String udpObservedHost;
    public int udpObservedPort;
    public long udpToken;
    public int acceptedUdpSmallPacketThresholdBytes;
    public boolean enableUdpDirect;
    public boolean enableUdpHolePunch;

    public HybridHelloAck() {
    }
}
