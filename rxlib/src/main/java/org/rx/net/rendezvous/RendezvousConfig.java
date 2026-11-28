package org.rx.net.rendezvous;

import lombok.Data;
import lombok.ToString;
import org.rx.net.EgressConfig;

import java.io.Serializable;

/**
 * Configuration for {@link RendezvousService} (authenticated RPC coordination + UDP endpoint observer).
 */
@Data
@ToString(exclude = {"authSecret"})
public final class RendezvousConfig implements Serializable {
    private static final long serialVersionUID = 1L;

    /** TCP listen port for Rendezvous RPC (0 = ephemeral). */
    private int rpcPort;
    /** UDP listen port for authenticated external endpoint observation (0 = ephemeral). */
    private int udpObservePort;
    /** Required Transport Core v2 shared secret for control-plane handshake HMAC + AES-128-GCM AEAD. */
    private String authSecret;
    /** Physical TCP egress policy (defaults to server AUTO supporting NONE/ZLIB_STORED/HTTP). */
    private EgressConfig egressConfig = EgressConfig.serverAutoAll();
    /** Whether public RRP Relay is co-located / advertised as fallback when direct TCP fails. */
    private boolean relayAvailable = true;

    // Bounded coordination & anti-abuse budgets
    private int maxGlobalPunchSessions = 1024;
    private int maxPunchSessionsPerNode = 16;
    private long punchSessionTtlMillis = 15_000L;
    private long observationTokenTtlMillis = 120_000L;
    private long observedCandidateTtlMillis = 60_000L;
    private int maxUdpObservePerIpPerSecond = 10;
    private int maxHostedServicesPerNode = 32;
    private int rpcHeartbeatTimeoutSeconds = 90;
}
