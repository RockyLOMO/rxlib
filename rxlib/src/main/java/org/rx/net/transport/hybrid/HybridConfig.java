package org.rx.net.transport.hybrid;

import lombok.Getter;
import lombok.Setter;
import org.rx.net.transport.TcpClientConfig;
import org.rx.net.transport.TcpServerConfig;
import org.rx.net.transport.UdpClientConfig;

import java.io.Serializable;
import java.net.InetSocketAddress;

@Getter
@Setter
public final class HybridConfig implements Serializable {
    private static final long serialVersionUID = -7380977808424964146L;

    private TcpClientConfig tcpClientConfig = new TcpClientConfig();
    private TcpServerConfig tcpServerConfig;
    private UdpClientConfig udpClientConfig = new UdpClientConfig();

    private int udpBindPort;
    private int udpSmallPacketThresholdBytes = 8 * 1024;
    private int udpProbeTimeoutMillis = 1500;
    private int udpProbeIntervalMillis = 120;
    private int udpProbeCount = 6;
    private int udpAckTimeoutMillis = 1200;
    private int maxUdpFailuresBeforeFallback = 3;
    private int maxUdpInflightMessagesPerSession = 1024;

    private boolean enableUdpDirect = true;
    private boolean enableUdpHolePunch = true;
    private InetSocketAddress rendezvousEndpoint;
}
