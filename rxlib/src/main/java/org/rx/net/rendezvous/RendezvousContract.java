package org.rx.net.rendezvous;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.rx.core.EventArgs;
import org.rx.core.EventPublisher;
import org.rx.net.EndpointCandidate;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * Authenticated Rendezvous control-plane RPC contract for candidate exchange and simultaneous NAT
 * punch coordination. Runs over AEAD-encrypted {@link org.rx.net.transport.core.TransportServer} v2;
 * carries control metadata only (zero business data).
 */
public interface RendezvousContract extends EventPublisher<RendezvousContract>, AutoCloseable {
    String EVENT_PUNCH_INVITE = "PUNCH_INVITE";

    RendezvousAuthResult authenticateNode(String nodeId, String nodeSecret,
                                          List<EndpointCandidate> initialCandidates,
                                          List<String> hostedServices);

    long reportCandidates(List<EndpointCandidate> candidates, List<String> hostedServices);

    PeerDiscoveryResult discoverPeer(String targetNodeId);

    PunchSessionResult requestPunchSession(String targetNodeId, String targetService);

    boolean heartbeat();

    @Override
    default void close() {
    }

    @Getter
    @Setter
    @ToString(exclude = {"obsKey"})
    final class RendezvousAuthResult implements Serializable {
        private static final long serialVersionUID = 1L;
        private String nodeId;
        private String tenant;
        private String obsTokenId;
        @JSONField(serialize = false)
        private byte[] obsKey;
        private int udpObserverPort;
        private long candidateGeneration;
        private long serverTimeMillis;
    }

    @Getter
    @Setter
    @ToString
    final class PeerDiscoveryResult implements Serializable {
        private static final long serialVersionUID = 1L;
        private String targetNodeId;
        private boolean online;
        private long candidateGeneration;
        private List<EndpointCandidate> candidates = Collections.emptyList();
        private boolean relayAvailable;
        private List<String> hostedServices = Collections.emptyList();
    }

    @Getter
    @Setter
    @ToString(exclude = {"punchKey"})
    final class PunchSessionResult implements Serializable {
        private static final long serialVersionUID = 1L;
        private long punchSessionId;
        private String initiatorNodeId;
        private String targetNodeId;
        private String targetService;
        @JSONField(serialize = false)
        private byte[] punchKey;
        private long expiresAtMillis;
        private long targetCandidateGen;
        private List<EndpointCandidate> targetCandidates = Collections.emptyList();
    }

    @Getter
    @Setter
    @ToString(exclude = {"punchKey"})
    final class PunchInviteEvent extends EventArgs {
        private static final long serialVersionUID = 1L;
        private long punchSessionId;
        private String initiatorNodeId;
        private String targetService;
        @JSONField(serialize = false)
        private byte[] punchKey;
        private long expiresAtMillis;
        private long initiatorCandidateGen;
        private List<EndpointCandidate> initiatorCandidates = Collections.emptyList();
    }
}
