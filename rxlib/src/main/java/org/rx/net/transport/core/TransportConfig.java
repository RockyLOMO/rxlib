package org.rx.net.transport.core;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.rx.net.rss.RssUdpMtuBudget;
import org.rx.net.transport.TcpClientConfig;
import org.rx.net.transport.TcpServerConfig;

import java.io.Serializable;
import java.net.InetSocketAddress;

/**
 * Shared transport configuration for raw {@link io.netty.buffer.ByteBuf} reliable streams.
 * Pure TCP mode ({@code enableAdaptiveUdp = false}) creates zero UDP sockets.
 */
@Getter
@Setter
@ToString
public final class TransportConfig implements Serializable {
    private static final long serialVersionUID = 1L;
    public static final int DEFAULT_UDP_MTU = 1200;
    public static final int DEFAULT_MAX_FRAME_BYTES = 16 * 1024 * 1024;
    public static final int DEFAULT_HANDSHAKE_TIMEOUT_MILLIS = 5000;

    private TcpClientConfig tcpClientConfig = new TcpClientConfig();
    private TcpServerConfig tcpServerConfig;
    /** Enables single-port daily AdaptiveTcpStream UDP fast path with TCP repair; false creates zero UDP sockets. */
    private boolean enableAdaptiveUdp = true;
    /** Server-side single UDP port for daily adaptive mode (0 = ephemeral). Port hopping is never enabled here. */
    private int udpBindPort;
    /** Net RSS UDP payload budget (0 or 256..65507). */
    private int udpMtu = DEFAULT_UDP_MTU;
    private int maxFrameBytes = DEFAULT_MAX_FRAME_BYTES;
    private int handshakeTimeoutMillis = DEFAULT_HANDSHAKE_TIMEOUT_MILLIS;
    /** Optional shared secret mixed into per-session AdaptiveUdpPort HMAC-SHA256 key derivation. */
    private String authSecret;
    private String peerId;

    public static TransportConfig dailyClient(InetSocketAddress serverEndpoint) {
        TransportConfig config = new TransportConfig();
        config.getTcpClientConfig().setServerEndpoint(serverEndpoint);
        config.setEnableAdaptiveUdp(true);
        return config;
    }

    public static TransportConfig pureTcpClient(InetSocketAddress serverEndpoint) {
        TransportConfig config = dailyClient(serverEndpoint);
        config.setEnableAdaptiveUdp(false);
        return config;
    }

    public static TransportConfig dailyServer(int listenPort) {
        TransportConfig config = new TransportConfig();
        config.setTcpServerConfig(new TcpServerConfig(listenPort));
        config.setEnableAdaptiveUdp(true);
        return config;
    }

    public static TransportConfig pureTcpServer(int listenPort) {
        TransportConfig config = dailyServer(listenPort);
        config.setEnableAdaptiveUdp(false);
        return config;
    }

    public void setUdpMtu(int udpMtu) {
        if (!RssUdpMtuBudget.valid(udpMtu)) {
            throw new IllegalArgumentException("Invalid transport UDP MTU: " + udpMtu);
        }
        this.udpMtu = udpMtu;
    }

    public void setMaxFrameBytes(int maxFrameBytes) {
        if (maxFrameBytes <= 0) {
            throw new IllegalArgumentException("maxFrameBytes must be positive: " + maxFrameBytes);
        }
        this.maxFrameBytes = maxFrameBytes;
    }
}
