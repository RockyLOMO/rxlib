package org.rx.net.rendezvous;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Disposable;
import org.rx.core.Strings;
import org.rx.net.EndpointCandidate;
import org.rx.net.EgressConfig;
import org.rx.net.registry.NodeRegistry;
import org.rx.net.rpc.Remoting;
import org.rx.net.rpc.RemotingClientHandle;
import org.rx.net.rpc.RpcServerConfig;
import org.rx.net.transport.core.TransportConfig;
import org.rx.net.transport.core.TransportServer;
import org.rx.net.transport.core.TransportSession;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.rx.core.Extends.tryClose;

/**
 * Authenticated Rendezvous control-plane server combining:
 * <ul>
 *     <li>AEAD-encrypted pure-TCP {@link Remoting} RPC over shared {@link TransportServer} v2 + {@link org.rx.net.EgressCodec}.</li>
 *     <li>Authenticated non-amplifying {@link UdpEndpointObserver.Server} recording server-verified {@code UDP_MAPPED} candidates.</li>
 *     <li>Shared {@link NodeRegistry} authority (subsystem {@code "rendezvous"}), ACL/tenant isolation, bounded punch
 *     session coordination, and immediate teardown on node disable / credential rotation.</li>
 * </ul>
 */
@Slf4j
public final class RendezvousService extends Disposable implements RendezvousContract {
    public static final String SUBSYSTEM_RENDEZVOUS = "rendezvous";
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final class NodeControlSession {
        final long transportSessionId;
        final String nodeId;
        volatile String tenant;
        final RemotingClientHandle handle;
        final String obsTokenId;
        final AtomicLong candidateGeneration = new AtomicLong(1L);
        volatile List<String> hostedServices = Collections.emptyList();

        NodeControlSession(long transportSessionId, String nodeId, String tenant,
                           RemotingClientHandle handle, String obsTokenId) {
            this.transportSessionId = transportSessionId;
            this.nodeId = nodeId;
            this.tenant = tenant;
            this.handle = handle;
            this.obsTokenId = obsTokenId;
        }
    }

    private static final class ActivePunchSession {
        final long punchSessionId;
        final String initiatorNodeId;
        final String targetNodeId;
        final long expiresAtMillis;

        ActivePunchSession(long punchSessionId, String initiatorNodeId, String targetNodeId, long expiresAtMillis) {
            this.punchSessionId = punchSessionId;
            this.initiatorNodeId = initiatorNodeId;
            this.targetNodeId = targetNodeId;
            this.expiresAtMillis = expiresAtMillis;
        }

        boolean isExpired(long now) {
            return now >= expiresAtMillis;
        }
    }

    private final RendezvousConfig config;
    private final NodeRegistry nodeRegistry;
    private final TransportServer rpcServer;
    private final UdpEndpointObserver.Server udpObserver;
    private final Consumer<NodeRegistry.OnlineNode> revokeListener;

    private final ConcurrentHashMap<Long, NodeControlSession> sessionsByTransportId = new ConcurrentHashMap<Long, NodeControlSession>();
    private final ConcurrentHashMap<String, NodeControlSession> sessionsByNodeId = new ConcurrentHashMap<String, NodeControlSession>();
    private final ConcurrentHashMap<Long, ActivePunchSession> punchSessions = new ConcurrentHashMap<Long, ActivePunchSession>();
    private final ConcurrentHashMap<String, AtomicInteger> nodePunchCounts = new ConcurrentHashMap<String, AtomicInteger>();
    private final Object punchLock = new Object();
    private final AtomicLong nextPunchId = new AtomicLong(1L);
    private final AtomicLong punchSessionsCreated = new AtomicLong();
    private final AtomicLong punchSessionsRejected = new AtomicLong();

    public RendezvousService(@NonNull RendezvousConfig config, @NonNull NodeRegistry nodeRegistry) {
        if (Strings.isEmpty(config.getAuthSecret())) {
            throw new IllegalArgumentException("RendezvousService requires non-empty authSecret for control-plane AEAD");
        }
        this.config = config;
        this.nodeRegistry = nodeRegistry;
        this.revokeListener = this::onNodeRevoked;

        this.udpObserver = new UdpEndpointObserver.Server(
                config.getUdpObservePort(),
                config.getMaxUdpObservePerIpPerSecond(),
                config.getObservedCandidateTtlMillis(),
                this::onUdpEndpointObserved);

        TransportConfig tc = TransportConfig.pureTcpServer(config.getRpcPort());
        tc.setEnableAdaptiveUdp(false);
        tc.setEnableCompression(false);
        tc.setAuthSecret(config.getAuthSecret());
        tc.setEnableEncryption(true);
        tc.setEgressConfig(config.getEgressConfig() != null
                ? config.getEgressConfig()
                : EgressConfig.serverAutoAll());
        tc.getTcpServerConfig().setHeartbeatTimeout(Math.max(0, config.getRpcHeartbeatTimeoutSeconds()));

        RpcServerConfig rpcConf = new RpcServerConfig(tc);
        rpcConf.setSensitiveMethods(Collections.singleton("authenticateNode"));

        this.nodeRegistry.addOnNodeRevokedListener(this.revokeListener);
        try {
            this.rpcServer = Remoting.register(this, rpcConf);
            this.rpcServer.onDisconnected.add((s, e) -> onTransportDisconnected(e.getValue()));
        } catch (Throwable t) {
            this.nodeRegistry.removeOnNodeRevokedListener(this.revokeListener);
            tryClose(this.udpObserver);
            throw t;
        }
    }

    public int rpcPort() {
        return rpcServer.listenPort();
    }

    public int udpObservePort() {
        return udpObserver.port();
    }

    public int authenticatedNodeCount() {
        return sessionsByNodeId.size();
    }

    public int activePunchSessionCount() {
        sweepExpiredPunchSessions(System.currentTimeMillis());
        return punchSessions.size();
    }

    public long punchSessionsCreated() {
        return punchSessionsCreated.get();
    }

    public long punchSessionsRejected() {
        return punchSessionsRejected.get();
    }

    public long obsRequestsReceived() {
        return udpObserver.requestsReceived();
    }

    public long obsRepliesSent() {
        return udpObserver.repliesSent();
    }

    public long obsRejectedCount() {
        return udpObserver.rejectedCount();
    }

    public long obsRxBytes() {
        return udpObserver.rxBytes();
    }

    public long obsTxBytes() {
        return udpObserver.txBytes();
    }

    /** Registers a custom UDP observation token (useful for direct observer testing). */
    public void registerObservationToken(@NonNull UdpEndpointObserver.ObservationToken token) {
        udpObserver.registerToken(token);
    }

    @Override
    public RendezvousAuthResult authenticateNode(String nodeId, String nodeSecret,
                                                 List<EndpointCandidate> initialCandidates,
                                                 List<String> hostedServices) {
        checkNotClosed();
        if (Strings.isEmpty(nodeId) || Strings.isEmpty(nodeSecret)) {
            throw new SecurityException("nodeId and nodeSecret are required");
        }
        RemotingClientHandle handle = Remoting.currentClientHandle();
        long transportId = handle.getSessionId();
        String trimmedNodeId = nodeId.trim();

        // Always strip client-claimed verified flags before persisting in NodeRegistry.
        List<EndpointCandidate> sanitized = nodeRegistry.sanitizeClientReportedCandidates(initialCandidates);
        byte[] obsKey = randomBytes(32);
        String obsTokenId = UdpEndpointObserver.hex(randomBytes(12));
        NodeControlSession boundState = new NodeControlSession(transportId, trimmedNodeId, "", handle, obsTokenId);
        boundState.hostedServices = sanitizeServiceNames(hostedServices);

        NodeRegistry.OnlineNode onlineNode = nodeRegistry.authenticateAndAttachSession(
                trimmedNodeId, nodeSecret, SUBSYSTEM_RENDEZVOUS, boundState, sanitized);
        boundState.tenant = onlineNode.getTenant();

        NodeControlSession prevByTransport = sessionsByTransportId.put(transportId, boundState);
        if (prevByTransport != null && prevByTransport != boundState) {
            udpObserver.revokeToken(prevByTransport.obsTokenId);
            if (!prevByTransport.nodeId.equals(boundState.nodeId)) {
                sessionsByNodeId.remove(prevByTransport.nodeId, prevByTransport);
                nodeRegistry.detachSubsystemSession(prevByTransport.nodeId, SUBSYSTEM_RENDEZVOUS, prevByTransport);
            }
        }
        NodeControlSession prevByNode = sessionsByNodeId.put(boundState.nodeId, boundState);
        if (prevByNode != null && prevByNode != boundState) {
            udpObserver.revokeToken(prevByNode.obsTokenId);
            sessionsByTransportId.remove(prevByNode.transportSessionId, prevByNode);
            rpcServer.closeSession(prevByNode.transportSessionId);
        }

        long now = System.currentTimeMillis();
        long obsExpires = now + Math.max(10_000L, config.getObservationTokenTtlMillis());
        udpObserver.registerToken(new UdpEndpointObserver.ObservationToken(
                obsTokenId, boundState.nodeId, boundState, obsKey, obsExpires));

        RendezvousAuthResult res = new RendezvousAuthResult();
        res.setNodeId(boundState.nodeId);
        res.setTenant(boundState.tenant);
        res.setObsTokenId(obsTokenId);
        res.setObsKey(obsKey);
        res.setUdpObserverPort(udpObserver.port());
        res.setCandidateGeneration(boundState.candidateGeneration.get());
        res.setServerTimeMillis(now);
        return res;
    }

    @Override
    public long reportCandidates(List<EndpointCandidate> candidates, List<String> hostedServices) {
        checkNotClosed();
        NodeControlSession caller = requireAuthenticatedCaller();
        List<EndpointCandidate> sanitized = nodeRegistry.sanitizeClientReportedCandidates(candidates);
        if (!nodeRegistry.heartbeatSubsystem(caller.nodeId, SUBSYSTEM_RENDEZVOUS, caller, sanitized)) {
            throw new SecurityException("Node session is no longer active");
        }
        if (hostedServices != null) {
            caller.hostedServices = sanitizeServiceNames(hostedServices);
        }
        return caller.candidateGeneration.incrementAndGet();
    }

    @Override
    public PeerDiscoveryResult discoverPeer(String targetNodeId) {
        checkNotClosed();
        NodeControlSession caller = requireAuthenticatedCaller();
        if (Strings.isEmpty(targetNodeId)) {
            throw new IllegalArgumentException("targetNodeId is required");
        }
        String normTarget = targetNodeId.trim();
        if (!nodeRegistry.isPeerAuthorized(caller.nodeId, normTarget)) {
            throw new SecurityException("Peer discovery unauthorized for target: " + normTarget);
        }
        NodeRegistry.OnlineNode targetOnline = nodeRegistry.getOnlineNode(normTarget);
        PeerDiscoveryResult res = new PeerDiscoveryResult();
        res.setTargetNodeId(normTarget);
        res.setRelayAvailable(config.isRelayAvailable());
        if (targetOnline == null) {
            res.setOnline(false);
            return res;
        }
        NodeControlSession targetCtrl = sessionsByNodeId.get(normTarget);
        res.setOnline(true);
        res.setCandidateGeneration(targetCtrl != null ? targetCtrl.candidateGeneration.get() : 1L);
        List<EndpointCandidate> cands = targetOnline.getCandidates();
        res.setCandidates(cands == null ? Collections.<EndpointCandidate>emptyList() : new ArrayList<EndpointCandidate>(cands));
        res.setHostedServices(targetCtrl != null ? new ArrayList<String>(targetCtrl.hostedServices) : Collections.<String>emptyList());
        return res;
    }

    @Override
    public PunchSessionResult requestPunchSession(String targetNodeId, String targetService) {
        checkNotClosed();
        NodeControlSession initiator = requireAuthenticatedCaller();
        if (Strings.isEmpty(targetNodeId) || Strings.isEmpty(targetService)) {
            punchSessionsRejected.incrementAndGet();
            throw new IllegalArgumentException("targetNodeId and targetService are required");
        }
        String normTarget = targetNodeId.trim();
        String normSvc = targetService.trim();
        if (!nodeRegistry.isPeerAuthorized(initiator.nodeId, normTarget)) {
            punchSessionsRejected.incrementAndGet();
            throw new SecurityException("Punch coordination unauthorized for target: " + normTarget);
        }
        NodeControlSession target = sessionsByNodeId.get(normTarget);
        NodeRegistry.OnlineNode targetOnline = nodeRegistry.getOnlineNode(normTarget);
        NodeRegistry.OnlineNode initiatorOnline = nodeRegistry.getOnlineNode(initiator.nodeId);
        if (target == null || targetOnline == null || initiatorOnline == null
                || !nodeRegistry.isSubsystemSessionActive(normTarget, SUBSYSTEM_RENDEZVOUS, target)) {
            punchSessionsRejected.incrementAndGet();
            throw new IllegalStateException("Target peer is not connected to Rendezvous: " + normTarget);
        }

        long now = System.currentTimeMillis();
        long expiresAt = now + Math.max(2000L, config.getPunchSessionTtlMillis());
        long punchSessionId;
        synchronized (punchLock) {
            sweepExpiredPunchSessionsLocked(now);
            int maxGlobal = Math.max(1, config.getMaxGlobalPunchSessions());
            int maxPerNode = Math.max(1, config.getMaxPunchSessionsPerNode());
            AtomicInteger initCount = nodePunchCounts.computeIfAbsent(initiator.nodeId, k -> new AtomicInteger());
            if (punchSessions.size() >= maxGlobal || initCount.get() >= maxPerNode) {
                punchSessionsRejected.incrementAndGet();
                throw new IllegalStateException("Rendezvous punch session budget exceeded");
            }
            punchSessionId = ((nextPunchId.incrementAndGet() & 0xFFFFFFFFL) << 32)
                    | (RANDOM.nextInt() & 0xFFFFFFFFL);
            if (punchSessionId == 0L) {
                punchSessionId = 1L;
            }
            ActivePunchSession active = new ActivePunchSession(punchSessionId, initiator.nodeId, normTarget, expiresAt);
            punchSessions.put(punchSessionId, active);
            initCount.incrementAndGet();
            punchSessionsCreated.incrementAndGet();
        }

        byte[] punchKey = randomBytes(32);
        List<EndpointCandidate> initCands = initiatorOnline.getCandidates() == null
                ? Collections.<EndpointCandidate>emptyList()
                : new ArrayList<EndpointCandidate>(initiatorOnline.getCandidates());
        List<EndpointCandidate> targetCands = targetOnline.getCandidates() == null
                ? Collections.<EndpointCandidate>emptyList()
                : new ArrayList<EndpointCandidate>(targetOnline.getCandidates());

        PunchInviteEvent invite = new PunchInviteEvent();
        invite.setPunchSessionId(punchSessionId);
        invite.setInitiatorNodeId(initiator.nodeId);
        invite.setTargetService(normSvc);
        invite.setPunchKey(punchKey.clone());
        invite.setExpiresAtMillis(expiresAt);
        invite.setInitiatorCandidateGen(initiator.candidateGeneration.get());
        invite.setInitiatorCandidates(initCands);

        // Push coordinated punch invite directly to target B over its authenticated AEAD RPC session.
        boolean pushed = Remoting.publishEventToClient(target.handle, EVENT_PUNCH_INVITE, invite);
        if (!pushed) {
            log.debug("Rendezvous punch invite delivery failed target={} punchSessionId={}", normTarget, punchSessionId);
        }

        PunchSessionResult res = new PunchSessionResult();
        res.setPunchSessionId(punchSessionId);
        res.setInitiatorNodeId(initiator.nodeId);
        res.setTargetNodeId(normTarget);
        res.setTargetService(normSvc);
        res.setPunchKey(punchKey);
        res.setExpiresAtMillis(expiresAt);
        res.setTargetCandidateGen(target.candidateGeneration.get());
        res.setTargetCandidates(targetCands);
        return res;
    }

    @Override
    public boolean heartbeat() {
        checkNotClosed();
        NodeControlSession caller = requireAuthenticatedCaller();
        return nodeRegistry.heartbeatSubsystem(caller.nodeId, SUBSYSTEM_RENDEZVOUS, caller, null);
    }

    private NodeControlSession requireAuthenticatedCaller() {
        RemotingClientHandle handle = Remoting.currentClientHandle();
        NodeControlSession state = sessionsByTransportId.get(handle.getSessionId());
        if (state == null || !nodeRegistry.isSubsystemSessionActive(state.nodeId, SUBSYSTEM_RENDEZVOUS, state)) {
            throw new SecurityException("Rendezvous session not authenticated or revoked");
        }
        return state;
    }

    private void onUdpEndpointObserved(UdpEndpointObserver.ObservationToken token, EndpointCandidate observed) {
        if (token == null || observed == null || isClosed()) {
            return;
        }
        boolean recorded = nodeRegistry.recordServerObservedCandidate(token.getNodeId(), token.getSessionRef(), observed);
        if (recorded) {
            NodeControlSession ctrl = sessionsByNodeId.get(token.getNodeId());
            if (ctrl != null && ctrl == token.getSessionRef()) {
                ctrl.candidateGeneration.incrementAndGet();
            }
        }
    }

    private void onTransportDisconnected(TransportSession session) {
        if (session == null) {
            return;
        }
        NodeControlSession state = sessionsByTransportId.remove(session.sessionId());
        if (state == null) {
            return;
        }
        udpObserver.revokeToken(state.obsTokenId);
        sessionsByNodeId.remove(state.nodeId, state);
        nodeRegistry.detachSubsystemSession(state.nodeId, SUBSYSTEM_RENDEZVOUS, state);
        cleanupNodePunchSessions(state.nodeId);
    }

    private void onNodeRevoked(NodeRegistry.OnlineNode evicted) {
        if (evicted == null || evicted.getNodeId() == null) {
            return;
        }
        NodeControlSession state = sessionsByNodeId.get(evicted.getNodeId());
        if (state == null) {
            return;
        }
        Object sub = evicted.getSubsystemSession(SUBSYSTEM_RENDEZVOUS);
        if (sub != null && sub != state) {
            return;
        }
        sessionsByNodeId.remove(state.nodeId, state);
        sessionsByTransportId.remove(state.transportSessionId, state);
        udpObserver.revokeToken(state.obsTokenId);
        cleanupNodePunchSessions(state.nodeId);
        rpcServer.closeSession(state.transportSessionId);
        log.info("RendezvousService disconnected revoked nodeId={}", state.nodeId);
    }

    private void cleanupNodePunchSessions(String nodeId) {
        synchronized (punchLock) {
            for (Map.Entry<Long, ActivePunchSession> e : punchSessions.entrySet()) {
                ActivePunchSession s = e.getValue();
                if (nodeId.equals(s.initiatorNodeId) || nodeId.equals(s.targetNodeId)) {
                    if (punchSessions.remove(e.getKey(), s)) {
                        AtomicInteger c = nodePunchCounts.get(s.initiatorNodeId);
                        if (c != null) {
                            c.decrementAndGet();
                        }
                    }
                }
            }
        }
    }

    private void sweepExpiredPunchSessions(long now) {
        synchronized (punchLock) {
            sweepExpiredPunchSessionsLocked(now);
        }
    }

    private void sweepExpiredPunchSessionsLocked(long now) {
        for (Map.Entry<Long, ActivePunchSession> e : punchSessions.entrySet()) {
            ActivePunchSession s = e.getValue();
            if (s.isExpired(now) && punchSessions.remove(e.getKey(), s)) {
                AtomicInteger c = nodePunchCounts.get(s.initiatorNodeId);
                if (c != null) {
                    c.decrementAndGet();
                }
            }
        }
    }

    private List<String> sanitizeServiceNames(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Collections.emptyList();
        }
        int cap = Math.max(1, config.getMaxHostedServicesPerNode());
        List<String> out = new ArrayList<String>(Math.min(cap, raw.size()));
        for (String s : raw) {
            if (out.size() >= cap) {
                break;
            }
            if (s != null && !s.trim().isEmpty() && s.trim().length() <= 128 && !out.contains(s.trim())) {
                out.add(s.trim());
            }
        }
        return Collections.unmodifiableList(out);
    }

    private static byte[] randomBytes(int len) {
        byte[] b = new byte[len];
        RANDOM.nextBytes(b);
        return b;
    }

    @Override
    protected void dispose() {
        nodeRegistry.removeOnNodeRevokedListener(revokeListener);
        sessionsByNodeId.clear();
        sessionsByTransportId.clear();
        punchSessions.clear();
        nodePunchCounts.clear();
        tryClose(udpObserver);
        tryClose(rpcServer);
    }
}
