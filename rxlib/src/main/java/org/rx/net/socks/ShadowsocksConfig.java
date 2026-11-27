package org.rx.net.socks;

import io.netty.util.AttributeKey;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.rx.net.BandwidthLimiter;
import org.rx.net.SocketConfig;
import org.rx.net.socks.encryption.ICrypto;

import java.net.InetSocketAddress;

@RequiredArgsConstructor
@Getter
@Setter
@ToString
public class ShadowsocksConfig extends SocketConfig {
    public static final AttributeKey<ShadowsocksServer> SVR = AttributeKey.valueOf("ssSvr");
    public static final AttributeKey<ICrypto> CIPHER = AttributeKey.valueOf("CIPHER");
    public static final AttributeKey<InetSocketAddress> REMOTE_DEST = AttributeKey.valueOf("REMOTE_DEST");

    private static final long serialVersionUID = 9144214925505451056L;
    private final InetSocketAddress serverEndpoint;
    private final String method;
    private final String password;
    private boolean useDedicatedCryptoGroup = false;
    private int readTimeoutSeconds = SocksConfig.DEF_READ_TIMEOUT_SECONDS;
    private int writeTimeoutSeconds;
    private int udpReadTimeoutSeconds = SocksConfig.DEF_UDP_READ_TIMEOUT_SECONDS;
    private int udpWriteTimeoutSeconds;
    private int udpOutboundPoolMaxSize = 8192;
    private int udpOutboundPoolWarnSize = 4096;
    private int udpOutboundPoolMaxPerSource = 256;
    /** Optional per-user/listener shared bidirectional bandwidth limiter. Null = unlimited fast path. */
    @ToString.Exclude
    private volatile transient BandwidthLimiter bandwidthLimiter;

    public void setUdpOutboundPoolMaxSize(int udpOutboundPoolMaxSize) {
        this.udpOutboundPoolMaxSize = Math.max(0, udpOutboundPoolMaxSize);
    }

    public void setUdpOutboundPoolWarnSize(int udpOutboundPoolWarnSize) {
        this.udpOutboundPoolWarnSize = Math.max(0, udpOutboundPoolWarnSize);
    }

    public void setUdpOutboundPoolMaxPerSource(int udpOutboundPoolMaxPerSource) {
        this.udpOutboundPoolMaxPerSource = Math.max(0, udpOutboundPoolMaxPerSource);
    }
}
