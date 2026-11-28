package org.rx.net.transport.core;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.rx.net.EgressCodec;
import org.rx.net.EgressConfig;
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
@ToString(exclude = {"authSecret"})
public final class TransportConfig implements Serializable {
    private static final long serialVersionUID = 2L;
    public static final int DEFAULT_UDP_MTU = 1200;
    public static final int DEFAULT_MAX_FRAME_BYTES = 16 * 1024 * 1024;
    public static final int DEFAULT_HANDSHAKE_TIMEOUT_MILLIS = 5000;
    public static final int DEFAULT_COMPRESSION_LEVEL = 3;

    private TcpClientConfig tcpClientConfig = new TcpClientConfig();
    private TcpServerConfig tcpServerConfig;
    /** Physical TCP egress framing (client explicit NONE/ZLIB_STORED/HTTP; server may use AUTO + allowedCodecs). */
    private EgressConfig egressConfig = EgressConfig.none();
    /** Enables single-port daily AdaptiveTcpStream UDP fast path with TCP repair; false creates zero UDP sockets. */
    private boolean enableAdaptiveUdp = true;
    /** Server-side single UDP port for daily adaptive mode (0 = ephemeral). Port hopping is never enabled here. */
    private int udpBindPort;
    /** Net RSS UDP payload budget (0 or 256..65507). */
    private int udpMtu = DEFAULT_UDP_MTU;
    private int maxFrameBytes = DEFAULT_MAX_FRAME_BYTES;
    private int handshakeTimeoutMillis = DEFAULT_HANDSHAKE_TIMEOUT_MILLIS;
    /** Optional post-handshake business zlib compression (installed strictly after authenticated handshake). */
    private boolean enableCompression;
    private int compressionLevel = DEFAULT_COMPRESSION_LEVEL;
    /** Optional post-handshake AES-128-GCM AEAD stream encryption (requires non-empty authSecret). */
    private boolean enableEncryption;
    /** Shared secret for handshake HMAC, per-session AEAD key derivation, and AdaptiveUdpPort HMAC-SHA256. */
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

    public void setEgressCodec(EgressCodec codec) {
        this.egressConfig = codec == EgressCodec.AUTO ? EgressConfig.serverAutoAll() : EgressConfig.server(codec);
    }

    public EgressConfig effectiveEgressConfig() {
        return egressConfig == null ? EgressConfig.none() : egressConfig;
    }

    public void setCompressionLevel(int level) {
        if (level < 1 || level > 9) {
            throw new IllegalArgumentException("compressionLevel must be 1..9: " + level);
        }
        this.compressionLevel = level;
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
