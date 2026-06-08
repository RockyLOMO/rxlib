package org.rx.net.transport;

import lombok.Getter;
import lombok.Setter;
import org.rx.net.SocketConfig;

/**
 * KCP ordered reliable UDP transport configuration.
 */
@Getter
@Setter
public class KcpClientConfig extends SocketConfig {
    private static final long serialVersionUID = 1L;

    private UdpClientCodec codec = FuryUdpClientCodec.createDefault();

    /**
     * KCP packet MTU before the outer RXKC UDP header is added.
     */
    private int mtu = 1200;
    private int noDelay = 1;
    private int intervalMillis = 10;
    private int fastResend = 2;
    private int noCongestionControl = 1;
    private int sendWindow = 128;
    private int receiveWindow = 128;

    private int sessionIdleTimeoutMillis = 60 * 1000;
    private int maxSessions = 4096;
    private int requestTimeoutMillis = 15 * 1000;
    private int maxPayloadBytes = 128 * 1024;
    private int maxPendingBytesPerSession = 4 * 1024 * 1024;
    private int maxPendingMessagesPerSession = 1024;

    private boolean flushOnSend = true;

    /**
     * Optional RXKC pre-shared key. When set, new sessions use authenticated
     * handshake packets and authenticated data datagrams.
     */
    private byte[] authenticationKey;

    /**
     * Allows an authenticated session to move to a new sender endpoint.
     * This option has no effect when authenticationKey is not configured.
     */
    private boolean allowNatRebinding = true;

    public byte[] getAuthenticationKey() {
        return authenticationKey == null ? null : authenticationKey.clone();
    }

    public void setAuthenticationKey(byte[] authenticationKey) {
        this.authenticationKey = authenticationKey == null || authenticationKey.length == 0
                ? null : authenticationKey.clone();
    }
}
