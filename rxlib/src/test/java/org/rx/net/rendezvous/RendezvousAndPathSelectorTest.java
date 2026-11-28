package org.rx.net.rendezvous;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.local.LocalAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.rx.net.EgressCodec;
import org.rx.net.EgressConfig;
import org.rx.net.EndpointCandidate;
import org.rx.net.EndpointCandidate.AddressFamily;
import org.rx.net.EndpointCandidate.CandidateType;
import org.rx.net.EndpointCandidate.Protocol;
import org.rx.net.Sockets;
import org.rx.net.registry.NodeRegistry;
import org.rx.net.registry.NodeRegistryConfig;
import org.rx.net.rendezvous.PathSelector.CandidatePlan;
import org.rx.net.rendezvous.PathSelector.ConnectRequest;
import org.rx.net.rendezvous.PathSelector.LocalTcpBinding;
import org.rx.net.rendezvous.PathSelector.PathSelectionReport;
import org.rx.net.rendezvous.PathSelector.PeerEndpointAcceptor;
import org.rx.net.rendezvous.PathSelector.RecentPathEntry;
import org.rx.net.rendezvous.PathSelector.SelectedConnection;
import org.rx.net.rendezvous.PathSelector.TcpCandidateTarget;
import org.rx.net.rendezvous.PathSelector.TcpPathType;
import org.rx.net.rendezvous.PathSelector.UdpPathType;
import org.rx.net.rendezvous.PeerUdpPuncher.LocalUdpBinding;
import org.rx.net.rendezvous.PeerUdpPuncher.UdpCandidatePair;
import org.rx.net.rendezvous.PeerUdpPuncher.VerifiedUdpPath;
import org.rx.net.rendezvous.RendezvousContract.PeerDiscoveryResult;
import org.rx.net.rendezvous.RendezvousContract.RendezvousAuthResult;
import org.rx.net.rpc.Remoting;
import org.rx.net.rpc.RpcClientConfig;
import org.rx.net.rss.tcp.AdaptiveUdpPort;
import org.rx.net.socks.RrpClient;
import org.rx.net.socks.RrpConfig;
import org.rx.net.socks.RrpServer;
import org.rx.net.transport.core.TransportConfig;
import org.rx.net.transport.core.TransportServer;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive verification of §4.1–§4.3 (direct-first multi-candidate {@link PathSelector},
 * {@link RendezvousService}, authenticated non-amplifying {@link UdpEndpointObserver},
 * simultaneous {@link PeerUdpPuncher}, verified UDP injection into {@link org.rx.net.rss.tcp.AdaptiveTcpHandler},
 * mid-stream UDP blackhole repair over on-demand RRP RAW Relay, security &amp; cache invalidation).
 */
class RendezvousAndPathSelectorTest {
    private static final String CONTROL_SECRET = "rv-control-secret-v2";
    private static final String RELAY_TOKEN = "rrp-relay-secret-v2";
    private static final String E2E_SECRET = "peer-e2e-aead-secret-ab";

    private NodeRegistry openRegistry(Path tempDir) {
        NodeRegistryConfig rc = new NodeRegistryConfig();
        rc.setEnabled(true);
        rc.setDbPath(tempDir.resolve("rv-reg-" + System.nanoTime()).toString());
        rc.setPbkdf2Iterations(1000);
        rc.setFlushPeriodMillis(0L);
        rc.setTtlSweepPeriodMillis(0L);
        NodeRegistry reg = new NodeRegistry(rc);
        reg.enrollNode("node-a", "tenant-1", "secret-a", Collections.singleton("peer"), null);
        reg.enrollNode("node-b", "tenant-1", "secret-b", Collections.singleton("peer"), null);
        reg.enrollNode("node-c", "tenant-2", "secret-c", Collections.singleton("peer"), null);
        return reg;
    }

    private RendezvousContract createRendezvousClient(int rpcPort, EgressCodec codec) {
        TransportConfig tc = TransportConfig.pureTcpClient(Sockets.newLoopbackEndpoint(rpcPort));
        tc.setAuthSecret(CONTROL_SECRET);
        tc.setEnableEncryption(true);
        tc.setEgressConfig(EgressConfig.client(codec));
        tc.getTcpClientConfig().setEnableReconnect(false);
        tc.getTcpClientConfig().setHeartbeatTimeout(0);
        RpcClientConfig<RendezvousContract> conf = new RpcClientConfig<RendezvousContract>(tc);
        conf.setSensitiveMethods(Collections.singleton("authenticateNode"));
        return Remoting.createFacade(RendezvousContract.class, conf);
    }

    private RrpClient connectRawRelayClient(int relayPort, String nodeId, String nodeSecret) throws Exception {
        RrpConfig conf = new RrpConfig();
        conf.setToken(RELAY_TOKEN);
        conf.setServerEndpoint("127.0.0.1:" + relayPort);
        conf.setEgressConfig(EgressConfig.client(EgressCodec.NONE));
        conf.setEnableReconnect(false);
        conf.setHeartbeatSeconds(0);
        conf.setNodeId(nodeId);
        conf.setNodeSecret(nodeSecret);
        RrpClient client = new RrpClient(conf);
        return client;
    }

    private int allocateClosedPort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static void waitFor(BooleanSupplier cond, long timeoutMs, String msg) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return;
            }
            Thread.sleep(30);
        }
        fail(msg);
    }

    @Test
    void multiCandidatePlanningFiltersExpiredDuplicatesUnsupportedAndTracksTruncation() {
        PathSelector.Config cfg = new PathSelector.Config();
        cfg.setMaxUdpPairsPerSession(3);
        cfg.setMaxTcpTargetsPerSession(3);
        PathSelector selector = new PathSelector(cfg);

        AdaptiveUdpPort p1 = AdaptiveUdpPort.bindShared(new InetSocketAddress("127.0.0.1", 0));
        AdaptiveUdpPort p2 = AdaptiveUdpPort.bindShared(new InetSocketAddress("127.0.0.1", 0));
        try {
            long now = 100_000L;
            List<LocalUdpBinding> locals = Arrays.asList(
                    new LocalUdpBinding("nic-a1", p1, AddressFamily.IPV4, "192.168.1.10", CandidateType.LAN, false),
                    new LocalUdpBinding("nic-a2", p2, AddressFamily.IPV4, "10.0.0.5", CandidateType.LAN, false)
            );
            List<EndpointCandidate> remotes = Arrays.asList(
                    // Valid LAN same subnet as nic-a1 (highest priority)
                    new EndpointCandidate(AddressFamily.IPV4, "192.168.1.20", 40001, Protocol.UDP, CandidateType.LAN, "b", now, 60_000L, false),
                    // Duplicate of above (filtered)
                    new EndpointCandidate(AddressFamily.IPV4, "192.168.1.20", 40001, Protocol.UDP, CandidateType.LAN, "b", now, 60_000L, false),
                    // Valid UDP_MAPPED
                    new EndpointCandidate(AddressFamily.IPV4, "203.0.113.5", 40002, Protocol.UDP, CandidateType.UDP_MAPPED, "obs", now, 60_000L, true),
                    // Expired candidate (filtered)
                    new EndpointCandidate(AddressFamily.IPV4, "10.0.0.20", 40003, Protocol.UDP, CandidateType.LAN, "b", now - 70_000L, 60_000L, false),
                    // HTTPS_PUBLIC port=0 (filtered)
                    new EndpointCandidate(AddressFamily.IPV4, "203.0.113.5", 0, Protocol.ANY, CandidateType.HTTPS_PUBLIC, "https", now, 60_000L, true),
                    // TCP_OBSERVED ephemeral client port (filtered)
                    new EndpointCandidate(AddressFamily.IPV4, "203.0.113.5", 55555, Protocol.TCP, CandidateType.TCP_OBSERVED, "tcp", now, 60_000L, true),
                    // IPv6 candidate with only IPv4 local sockets (family mismatch)
                    new EndpointCandidate(AddressFamily.IPV6, "2001:4860:4860::8888", 40004, Protocol.UDP, CandidateType.GLOBAL_V6, "b", now, 60_000L, false),
                    // TCP-only candidate (protocol mismatch for UDP)
                    new EndpointCandidate(AddressFamily.IPV4, "10.0.0.21", 40005, Protocol.TCP, CandidateType.LAN, "b", now, 60_000L, false)
            );

            CandidatePlan<UdpCandidatePair> plan = selector.planUdpPairs(locals, remotes, null, now);
            assertEquals(3, plan.getScheduled().size(), "must respect maxUdpPairsPerSession=3");
            assertEquals(4, plan.getTotalConsidered(), "2 local IPv4 x 2 valid remote UDP = 4 pairs considered");
            assertEquals(1, plan.getTruncatedCount(), "1 pair truncated by budget");
            // Top priority must be same-subnet LAN pair nic-a1 -> 192.168.1.20:40001
            assertEquals("nic-a1->192.168.1.20:40001", plan.getScheduled().get(0).pairKey());

            String reasons = String.join(",", plan.getUncheckedReasons());
            assertTrue(reasons.contains("udp:duplicate:192.168.1.20:40001"), reasons);
            assertTrue(reasons.contains("udp:expired:10.0.0.20:40003"), reasons);
            assertTrue(reasons.contains("udp:unsupported_type:HTTPS_PUBLIC"), reasons);
            assertTrue(reasons.contains("udp:unsupported_type:TCP_OBSERVED"), reasons);
            assertTrue(reasons.contains("udp:family_mismatch"), reasons);
            assertTrue(reasons.contains("udp:protocol_mismatch"), reasons);
            assertTrue(reasons.contains("udp:pair_budget_truncated:1"), reasons);

            // TCP plan must reject UDP_MAPPED, HTTPS_PUBLIC, TCP_OBSERVED, and UDP-only candidates
            List<LocalTcpBinding> tcpLocals = Arrays.asList(
                    new LocalTcpBinding("tcp-a1", AddressFamily.IPV4, "127.0.0.1", CandidateType.LAN, false)
            );
            CandidatePlan<TcpCandidateTarget> tcpPlan = selector.planDirectTcpTargets(tcpLocals, remotes, null, now);
            assertEquals(1, tcpPlan.getScheduled().size());
            assertEquals("tcp-a1->10.0.0.21:40005", tcpPlan.getScheduled().get(0).pairKey());
            String tcpReasons = String.join(",", tcpPlan.getUncheckedReasons());
            assertTrue(tcpReasons.contains("tcp:unsupported_type:UDP_MAPPED"), tcpReasons);
            assertTrue(tcpReasons.contains("tcp:unsupported_type:HTTPS_PUBLIC"), tcpReasons);
            assertTrue(tcpReasons.contains("tcp:unsupported_type:TCP_OBSERVED"), tcpReasons);
        } finally {
            p1.close();
            p2.close();
        }
    }

    @Test
    @Timeout(25)
    void nonDefaultCandidatePairReachableUsesDirectTcpAndUdpWithZeroRelay(@TempDir Path tempDir) throws Exception {
        NodeRegistry reg = openRegistry(tempDir);
        RrpConfig relayConf = new RrpConfig();
        relayConf.setToken(RELAY_TOKEN);
        relayConf.setBindPort(0);
        relayConf.setHeartbeatSeconds(0);
        RrpServer relayServer = new RrpServer(relayConf, reg);

        // B direct TCP server (with daily adaptive UDP enabled)
        TransportConfig bDirectConf = TransportConfig.dailyServer(0);
        bDirectConf.setAuthSecret(E2E_SECRET);
        bDirectConf.setEnableEncryption(true);
        bDirectConf.setEnableCompression(true);
        TransportServer bDirectServer = new TransportServer(bDirectConf);
        bDirectServer.setReceiveHandler((session, frame) -> session.send(frame.retainedDuplicate()));
        bDirectServer.start();

        // Both A and B have shared AdaptiveUdpPorts for direct UDP check (plus unreachable B1 candidates)
        AdaptiveUdpPort udpA = AdaptiveUdpPort.bindShared(new InetSocketAddress("127.0.0.1", 0));
        AdaptiveUdpPort udpB = AdaptiveUdpPort.bindShared(new InetSocketAddress("127.0.0.1", 0));
        PeerUdpPuncher puncherA = new PeerUdpPuncher();
        PeerUdpPuncher puncherB = new PeerUdpPuncher();
        LocalUdpBinding bindA = new LocalUdpBinding("a-loop", udpA, AddressFamily.IPV4, "127.0.0.1", CandidateType.LAN, false);
        LocalUdpBinding bindB = new LocalUdpBinding("b-loop", udpB, AddressFamily.IPV4, "127.0.0.1", CandidateType.LAN, false);
        puncherA.attachToBinding(bindA, null);
        puncherB.attachToBinding(bindB, null);

        RrpClient relayA = connectRawRelayClient(relayServer.listenPort(), "node-a", "secret-a");
        RrpClient relayB = connectRawRelayClient(relayServer.listenPort(), "node-b", "secret-b");

        PathSelector selectorA = new PathSelector();
        PathSelector selectorB = new PathSelector();
        PeerEndpointAcceptor acceptorB = new PeerEndpointAcceptor(selectorB, puncherB, () -> Collections.singletonList(bindB));
        acceptorB.bindTransportServer(bDirectServer);

        SelectedConnection selected = null;
        try {
            relayA.connectAsync().get(5, TimeUnit.SECONDS);
            relayB.connectAsync().get(5, TimeUnit.SECONDS);
            relayA.awaitRawAuthenticated().get(5, TimeUnit.SECONDS);
            relayB.awaitRawAuthenticated().get(5, TimeUnit.SECONDS);

            int closedTcpPort = allocateClosedPort();
            int closedUdpPort = allocateClosedPort();
            long now = System.currentTimeMillis();
            long punchSessionId = 7777L;
            byte[] punchKey = new byte[32];
            new SecureRandom().nextBytes(punchKey);

            // B starts responding on direct UDP punch
            EndpointCandidate candA = new EndpointCandidate(AddressFamily.IPV4, "127.0.0.1", udpA.port(),
                    Protocol.UDP, CandidateType.LAN, "a", now, 60_000L, false);
            acceptorB.startDirectPunchResponder("node-a", punchSessionId, punchKey, Collections.singletonList(candA));

            // B advertises unreachable B1 first, then reachable non-default B2!
            List<EndpointCandidate> bCandidates = Arrays.asList(
                    new EndpointCandidate(AddressFamily.IPV4, "127.0.0.1", closedTcpPort, Protocol.TCP, CandidateType.LAN, "b-bad", now, 60_000L, false),
                    new EndpointCandidate(AddressFamily.IPV4, "127.0.0.1", bDirectServer.listenPort(), Protocol.TCP, CandidateType.HOST, "b-good", now, 60_000L, false),
                    new EndpointCandidate(AddressFamily.IPV4, "127.0.0.1", closedUdpPort, Protocol.UDP, CandidateType.LAN, "b-bad", now, 60_000L, false),
                    new EndpointCandidate(AddressFamily.IPV4, "127.0.0.1", udpB.port(), Protocol.UDP, CandidateType.HOST, "b-good", now, 60_000L, false)
            );

            TransportConfig e2eTemplate = TransportConfig.dailyClient(null);
            e2eTemplate.setAuthSecret(E2E_SECRET);
            e2eTemplate.setEnableEncryption(true);
            e2eTemplate.setEnableCompression(true);

            ConnectRequest req = new ConnectRequest();
            req.setLocalNodeId("node-a");
            req.setTargetNodeId("node-b");
            req.setTargetService("svc-direct");
            req.setLocalTcpBindings(Collections.singletonList(
                    new LocalTcpBinding("a-tcp", AddressFamily.IPV4, "127.0.0.1", CandidateType.LAN, false)));
            req.setLocalUdpBindings(Collections.singletonList(bindA));
            req.setRemoteCandidates(bCandidates);
            req.setE2eTransportTemplate(e2eTemplate);
            req.setRrpRelayClient(relayA);
            req.setPuncher(puncherA);
            req.setDirectPunchSessionId(punchSessionId);
            req.setDirectPunchKey(punchKey);

            selected = selectorA.connectPeerAsync(req).get(8, TimeUnit.SECONDS);
            PathSelectionReport report = selected.getReport();
            assertEquals(TcpPathType.DIRECT, report.getTcpPathType());
            assertFalse(report.isRelayOpened(), "Direct TCP win must NEVER open RRP Relay");
            assertEquals(0L, relayServer.rawStreamsOpenedCount(), "RRP RAW business streams opened must be 0");
            assertEquals(0L, relayServer.rawRelayIngressBytes(), "RRP RAW business ingress bytes must be 0");
            assertEquals(0L, relayServer.rawRelayEgressBytes(), "RRP RAW business egress bytes must be 0");

            VerifiedUdpPath udpPath = selected.getUdpFuture().get(5, TimeUnit.SECONDS);
            assertNotNull(udpPath, "Non-default UDP pair must verify bidirectionally");
            assertEquals(udpB.port(), udpPath.getVerifiedPeerAddress().getPort());
            assertEquals(UdpPathType.DIRECT_OR_PUNCH, report.getUdpPathType());

            // Verify E2E AEAD data roundtrip + adaptive UDP promotion
            CountDownLatch echoLatch = new CountDownLatch(1);
            AtomicReference<String> echoed = new AtomicReference<String>();
            selected.session().setReceiveHandler((s, f) -> {
                echoed.set(f.toString(StandardCharsets.UTF_8));
                echoLatch.countDown();
            });
            selected.session().send(Unpooled.copiedBuffer("direct-first-ok", StandardCharsets.UTF_8));
            assertTrue(echoLatch.await(5, TimeUnit.SECONDS));
            assertEquals("direct-first-ok", echoed.get());
            assertEquals(0L, relayServer.rawStreamsOpenedCount());
            assertEquals(0L, relayServer.rawRelayIngressBytes());
        } finally {
            if (selected != null) selected.close();
            acceptorB.close();
            puncherA.close();
            puncherB.close();
            udpA.close();
            udpB.close();
            relayA.close();
            relayB.close();
            bDirectServer.close();
            relayServer.close();
            reg.close();
        }
    }

    @Test
    @Timeout(20)
    void directTcpReachableWithUdpBlockedUsesPureDirectTcpAndZeroRelay(@TempDir Path tempDir) throws Exception {
        NodeRegistry reg = openRegistry(tempDir);
        RrpConfig relayConf = new RrpConfig();
        relayConf.setToken(RELAY_TOKEN);
        relayConf.setBindPort(0);
        relayConf.setHeartbeatSeconds(0);
        RrpServer relayServer = new RrpServer(relayConf, reg);

        TransportConfig bDirectConf = TransportConfig.pureTcpServer(0);
        bDirectConf.setAuthSecret(E2E_SECRET);
        bDirectConf.setEnableEncryption(true);
        TransportServer bDirectServer = new TransportServer(bDirectConf);
        bDirectServer.setReceiveHandler((s, f) -> s.send(f.retainedDuplicate()));
        bDirectServer.start();

        RrpClient relayA = connectRawRelayClient(relayServer.listenPort(), "node-a", "secret-a");
        PathSelector selector = new PathSelector();
        SelectedConnection selected = null;
        try {
            relayA.connectAsync().get(5, TimeUnit.SECONDS);
            relayA.awaitRawAuthenticated().get(5, TimeUnit.SECONDS);

            long now = System.currentTimeMillis();
            TransportConfig e2eTemplate = TransportConfig.pureTcpClient(null);
            e2eTemplate.setAuthSecret(E2E_SECRET);
            e2eTemplate.setEnableEncryption(true);

            ConnectRequest req = new ConnectRequest();
            req.setLocalNodeId("node-a");
            req.setTargetNodeId("node-b");
            req.setTargetService("svc-tcp");
            req.setRemoteCandidates(Collections.singletonList(
                    new EndpointCandidate(AddressFamily.IPV4, "127.0.0.1", bDirectServer.listenPort(),
                            Protocol.TCP, CandidateType.LAN, "b", now, 60_000L, false)));
            req.setE2eTransportTemplate(e2eTemplate);
            req.setRrpRelayClient(relayA);

            selected = selector.connectPeerAsync(req).get(5, TimeUnit.SECONDS);
            assertEquals(TcpPathType.DIRECT, selected.getReport().getTcpPathType());
            assertEquals(UdpPathType.NONE, selected.getReport().getUdpPathType());
            assertFalse(selected.getReport().isRelayOpened());
            assertEquals(0L, relayServer.rawStreamsOpenedCount());
            assertEquals(0L, relayServer.rawRelayIngressBytes());

            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<String> back = new AtomicReference<String>();
            selected.session().setReceiveHandler((s, f) -> {
                back.set(f.toString(StandardCharsets.UTF_8));
                latch.countDown();
            });
            selected.session().send(Unpooled.copiedBuffer("pure-direct-tcp", StandardCharsets.UTF_8));
            assertTrue(latch.await(5, TimeUnit.SECONDS));
            assertEquals("pure-direct-tcp", back.get());
        } finally {
            if (selected != null) selected.close();
            relayA.close();
            bDirectServer.close();
            relayServer.close();
            reg.close();
        }
    }

    @Test
    @Timeout(30)
    void dualNatDirectTcpBlockedSimultaneousPunchPromotesUdpOverOnDemandRelayAndSurvivesMidStreamUdpBlackhole(@TempDir Path tempDir) throws Exception {
        NodeRegistry reg = openRegistry(tempDir);

        // Public VPS components sharing ONE NodeRegistry: RendezvousService + RrpServer RAW Relay
        RendezvousConfig rvConf = new RendezvousConfig();
        rvConf.setAuthSecret(CONTROL_SECRET);
        rvConf.setRpcPort(0);
        rvConf.setUdpObservePort(0);
        RendezvousService rendezvous = new RendezvousService(rvConf, reg);

        RrpConfig relayConf = new RrpConfig();
        relayConf.setToken(RELAY_TOKEN);
        relayConf.setBindPort(0);
        relayConf.setHeartbeatSeconds(0);
        RrpServer relayServer = new RrpServer(relayConf, reg);

        // Node B hosts E2E AEAD + deferred-adaptive TransportServer on LocalAddress behind RRP RAW Relay
        LocalAddress bLocalCarrier = new LocalAddress("b-dualnat-" + System.nanoTime());
        TransportConfig bTcConf = TransportConfig.dailyServer(0);
        bTcConf.setAuthSecret(E2E_SECRET);
        bTcConf.setEnableEncryption(true);
        bTcConf.setEnableCompression(true);
        TransportServer bTransportServer = new TransportServer(bTcConf);
        bTransportServer.setReceiveHandler((s, f) -> s.send(f.retainedDuplicate()));
        bTransportServer.startLocal(bLocalCarrier);

        // Shared UDP sockets (same socket spans observation -> punch -> AdaptiveUdpPort!)
        AdaptiveUdpPort udpA = AdaptiveUdpPort.bindShared(new InetSocketAddress("127.0.0.1", 0));
        AdaptiveUdpPort udpB = AdaptiveUdpPort.bindShared(new InetSocketAddress("127.0.0.1", 0));
        UdpEndpointObserver.Client obsClientA = new UdpEndpointObserver.Client(udpA);
        UdpEndpointObserver.Client obsClientB = new UdpEndpointObserver.Client(udpB);
        PeerUdpPuncher puncherA = new PeerUdpPuncher();
        PeerUdpPuncher puncherB = new PeerUdpPuncher();
        LocalUdpBinding bindA = new LocalUdpBinding("a-udp", udpA, AddressFamily.IPV4, "127.0.0.1", CandidateType.LAN, false);
        LocalUdpBinding bindB = new LocalUdpBinding("b-udp", udpB, AddressFamily.IPV4, "127.0.0.1", CandidateType.LAN, false);
        puncherA.attachToBinding(bindA, obsClientA);
        puncherB.attachToBinding(bindB, obsClientB);

        RendezvousContract rvClientA = createRendezvousClient(rendezvous.rpcPort(), EgressCodec.HTTP);
        RendezvousContract rvClientB = createRendezvousClient(rendezvous.rpcPort(), EgressCodec.ZLIB_STORED);
        RrpClient relayA = connectRawRelayClient(relayServer.listenPort(), "node-a", "secret-a");
        RrpClient relayB = connectRawRelayClient(relayServer.listenPort(), "node-b", "secret-b");
        relayB.registerRawService("dual-svc", bLocalCarrier);

        PathSelector selectorA = new PathSelector();
        selectorA.getConfig().setDirectTcpBudgetMillis(400L);
        PathSelector selectorB = new PathSelector();
        PeerEndpointAcceptor acceptorB = new PeerEndpointAcceptor(selectorB, puncherB, () -> Collections.singletonList(bindB));
        acceptorB.bindTransportServer(bTransportServer);
        acceptorB.attachToRendezvous(rvClientB);

        SelectedConnection selected = null;
        try {
            // 1) Authenticate both A & B to Rendezvous AND RRP Relay simultaneously on shared NodeRegistry (no cross-eviction!)
            RendezvousAuthResult authA = rvClientA.authenticateNode("node-a", "secret-a", Collections.<EndpointCandidate>emptyList(), Collections.<String>emptyList());
            RendezvousAuthResult authB = rvClientB.authenticateNode("node-b", "secret-b", Collections.<EndpointCandidate>emptyList(), Collections.singletonList("dual-svc"));
            relayA.connectAsync().get(5, TimeUnit.SECONDS);
            relayB.connectAsync().get(5, TimeUnit.SECONDS);
            relayA.awaitRawAuthenticated().get(5, TimeUnit.SECONDS);
            relayB.awaitRawAuthenticated().get(5, TimeUnit.SECONDS);

            // 2) Authenticated UDP endpoint observation on the EXACT same AdaptiveUdpPort sockets!
            InetSocketAddress observerEp = Sockets.newLoopbackEndpoint(authA.getUdpObserverPort());
            InetSocketAddress mappedA = obsClientA.observeAsync(observerEp, authA.getObsTokenId(), authA.getObsKey(), 3, 2000L).get(5, TimeUnit.SECONDS);
            InetSocketAddress mappedB = obsClientB.observeAsync(observerEp, authB.getObsTokenId(), authB.getObsKey(), 3, 2000L).get(5, TimeUnit.SECONDS);
            assertEquals(udpA.port(), mappedA.getPort());
            assertEquals(udpB.port(), mappedB.getPort());
            assertTrue(rendezvous.obsRepliesSent() >= 2L);

            // Report unreachable direct TCP candidate for B (simulating NAT blocking direct TCP)
            int blockedTcpPort = allocateClosedPort();
            rvClientB.reportCandidates(Collections.singletonList(
                    new EndpointCandidate(AddressFamily.IPV4, "127.0.0.1", blockedTcpPort, Protocol.TCP, CandidateType.LAN, "b-nat", System.currentTimeMillis(), 60_000L, true)
            ), Collections.singletonList("dual-svc"));

            // Discover B via Rendezvous: must preserve server-verified UDP_MAPPED AND strip client-claimed verified=true on LAN TCP!
            PeerDiscoveryResult discB = rvClientA.discoverPeer("node-b");
            assertTrue(discB.isOnline());
            boolean sawVerifiedUdpMapped = false;
            for (EndpointCandidate c : discB.getCandidates()) {
                if (c.getCandidateType() == CandidateType.UDP_MAPPED && c.getPort() == udpB.port()) {
                    assertTrue(c.isVerified(), "server-observed UDP_MAPPED must remain verified=true");
                    sawVerifiedUdpMapped = true;
                }
                if (c.getCandidateType() == CandidateType.LAN) {
                    assertFalse(c.isVerified(), "client-reported LAN candidate must be forced verified=false");
                }
            }
            assertTrue(sawVerifiedUdpMapped, "Rendezvous must serve server-observed UDP_MAPPED candidate");

            // 3) PathSelector: direct TCP fails -> on-demand RRP RAW Relay + Rendezvous simultaneous UDP punch!
            TransportConfig e2eTemplate = TransportConfig.dailyClient(null);
            e2eTemplate.setAuthSecret(E2E_SECRET);
            e2eTemplate.setEnableEncryption(true);
            e2eTemplate.setEnableCompression(true);

            ConnectRequest req = new ConnectRequest();
            req.setLocalNodeId("node-a");
            req.setTargetNodeId("node-b");
            req.setTargetService("dual-svc");
            req.setRemoteCandidateGen(discB.getCandidateGeneration());
            req.setLocalUdpBindings(Collections.singletonList(bindA));
            req.setRemoteCandidates(discB.getCandidates());
            req.setE2eTransportTemplate(e2eTemplate);
            req.setRendezvousClient(rvClientA);
            req.setRrpRelayClient(relayA);
            req.setPuncher(puncherA);

            selected = selectorA.connectPeerAsync(req).get(10, TimeUnit.SECONDS);
            assertEquals(TcpPathType.RELAY, selected.getReport().getTcpPathType());
            assertTrue(selected.getReport().isRelayOpened());
            assertEquals(1L, relayServer.rawStreamsOpenedCount());
            assertTrue(selected.session().isEncrypted());

            // Simultaneous UDP punch must verify and upgrade the Relay-backed session to usingUdp() == true!
            VerifiedUdpPath udpPath = selected.getUdpFuture().get(6, TimeUnit.SECONDS);
            assertNotNull(udpPath, "Simultaneous Rendezvous punch must succeed");
            assertEquals(udpB.port(), udpPath.getVerifiedPeerAddress().getPort());
            assertEquals(UdpPathType.DIRECT_OR_PUNCH, selected.getReport().getUdpPathType());

            final SelectedConnection finalSel = selected;
            waitFor(() -> finalSel.session().isUsingUdp(), 5000,
                    "Relay-backed session must promote to verified direct UDP fast path (usingUdp == true)");

            // Send payload while UDP fast path is active
            AtomicReference<CountDownLatch> rxLatch = new AtomicReference<CountDownLatch>(new CountDownLatch(1));
            AtomicReference<byte[]> rxData = new AtomicReference<byte[]>();
            selected.session().setReceiveHandler((s, f) -> {
                byte[] b = new byte[f.readableBytes()];
                f.readBytes(b);
                rxData.set(b);
                rxLatch.get().countDown();
            });

            byte[] fastPayload = new byte[32 * 1024];
            new Random(101L).nextBytes(fastPayload);
            selected.session().send(Unpooled.wrappedBuffer(fastPayload));
            assertTrue(rxLatch.get().await(8, TimeUnit.SECONDS), "echo over punched UDP fast path must complete");
            assertArrayEquals(fastPayload, rxData.get());

            // 4) Mid-stream UDP blackhole -> unregister UDP peer -> AdaptiveTcpStream repairs seamlessly over Relay TCP!
            udpA.unregister(selected.session().sessionId());
            rxLatch.set(new CountDownLatch(1));
            byte[] repairPayload = new byte[48 * 1024];
            new Random(202L).nextBytes(repairPayload);
            selected.session().send(Unpooled.wrappedBuffer(repairPayload));
            assertTrue(rxLatch.get().await(10, TimeUnit.SECONDS), "mid-stream UDP blackhole must repair losslessly over Relay TCP");
            assertArrayEquals(repairPayload, rxData.get());
        } finally {
            if (selected != null) selected.close();
            acceptorB.close();
            puncherA.close();
            puncherB.close();
            udpA.close();
            udpB.close();
            rvClientA.close();
            rvClientB.close();
            relayA.close();
            relayB.close();
            bTransportServer.close();
            rendezvous.close();
            relayServer.close();
            reg.close();
        }
    }

    @Test
    @Timeout(20)
    void directTcpBlockedAndUdpBlockedFallsBackToPureTcpRelayAndAllBlockedFailsExplicitly(@TempDir Path tempDir) throws Exception {
        NodeRegistry reg = openRegistry(tempDir);
        RrpConfig relayConf = new RrpConfig();
        relayConf.setToken(RELAY_TOKEN);
        relayConf.setBindPort(0);
        relayConf.setHeartbeatSeconds(0);
        RrpServer relayServer = new RrpServer(relayConf, reg);

        LocalAddress bLocal = new LocalAddress("b-tcponly-" + System.nanoTime());
        TransportConfig bTcConf = TransportConfig.pureTcpServer(0);
        bTcConf.setAuthSecret(E2E_SECRET);
        bTcConf.setEnableEncryption(true);
        TransportServer bServer = new TransportServer(bTcConf);
        bServer.setReceiveHandler((s, f) -> s.send(f.retainedDuplicate()));
        bServer.startLocal(bLocal);

        RrpClient relayA = connectRawRelayClient(relayServer.listenPort(), "node-a", "secret-a");
        RrpClient relayB = connectRawRelayClient(relayServer.listenPort(), "node-b", "secret-b");
        relayB.registerRawService("tcp-only-svc", bLocal);

        PathSelector selector = new PathSelector();
        selector.getConfig().setDirectTcpBudgetMillis(300L);
        SelectedConnection selected = null;
        try {
            relayA.connectAsync().get(5, TimeUnit.SECONDS);
            relayB.connectAsync().get(5, TimeUnit.SECONDS);
            relayA.awaitRawAuthenticated().get(5, TimeUnit.SECONDS);
            relayB.awaitRawAuthenticated().get(5, TimeUnit.SECONDS);

            int blockedPort = allocateClosedPort();
            TransportConfig e2eTemplate = TransportConfig.pureTcpClient(null);
            e2eTemplate.setAuthSecret(E2E_SECRET);
            e2eTemplate.setEnableEncryption(true);

            // Case 1: Direct TCP blocked, UDP blocked, Relay available -> pure TCP Relay succeeds!
            ConnectRequest req1 = new ConnectRequest();
            req1.setLocalNodeId("node-a");
            req1.setTargetNodeId("node-b");
            req1.setTargetService("tcp-only-svc");
            req1.setRemoteCandidates(Collections.singletonList(
                    new EndpointCandidate(AddressFamily.IPV4, "127.0.0.1", blockedPort, Protocol.TCP, CandidateType.LAN, "b", System.currentTimeMillis(), 60_000L, false)));
            req1.setE2eTransportTemplate(e2eTemplate);
            req1.setRrpRelayClient(relayA);

            selected = selector.connectPeerAsync(req1).get(8, TimeUnit.SECONDS);
            assertEquals(TcpPathType.RELAY, selected.getReport().getTcpPathType());
            assertEquals(UdpPathType.NONE, selected.getReport().getUdpPathType());
            assertTrue(selected.getReport().isRelayOpened());
            assertFalse(selected.session().isUsingUdp());

            CountDownLatch echoLatch = new CountDownLatch(1);
            AtomicReference<String> echoed = new AtomicReference<String>();
            selected.session().setReceiveHandler((s, f) -> {
                echoed.set(f.toString(StandardCharsets.UTF_8));
                echoLatch.countDown();
            });
            selected.session().send(Unpooled.copiedBuffer("tcp-only-relay-ok", StandardCharsets.UTF_8));
            assertTrue(echoLatch.await(5, TimeUnit.SECONDS));
            assertEquals("tcp-only-relay-ok", echoed.get());
            selected.close();
            selected = null;

            // Case 2: All reliable paths blocked (direct TCP closed AND Relay unavailable), even if UDP is reachable -> MUST fail explicitly!
            ConnectRequest req2 = new ConnectRequest();
            req2.setLocalNodeId("node-a");
            req2.setTargetNodeId("node-b");
            req2.setTargetService("tcp-only-svc");
            req2.setRemoteCandidates(Collections.singletonList(
                    new EndpointCandidate(AddressFamily.IPV4, "127.0.0.1", blockedPort, Protocol.TCP, CandidateType.LAN, "b", System.currentTimeMillis(), 60_000L, false)));
            req2.setE2eTransportTemplate(e2eTemplate);
            req2.setRrpRelayClient(null); // Relay unavailable

            ExecutionException ex = assertThrows(ExecutionException.class,
                    () -> selector.connectPeerAsync(req2).get(5, TimeUnit.SECONDS));
            assertInstanceOf(ConnectException.class, ex.getCause());
        } finally {
            if (selected != null) selected.close();
            relayA.close();
            relayB.close();
            bServer.close();
            relayServer.close();
            reg.close();
        }
    }

    @Test
    @Timeout(20)
    void securityForgedUdpPacketsCrossTenantAndRevocationAndCacheInvalidation(@TempDir Path tempDir) throws Exception {
        NodeRegistry reg = openRegistry(tempDir);
        RendezvousConfig rvConf = new RendezvousConfig();
        rvConf.setAuthSecret(CONTROL_SECRET);
        rvConf.setRpcPort(0);
        rvConf.setUdpObservePort(0);
        RendezvousService rendezvous = new RendezvousService(rvConf, reg);

        RendezvousContract clientA = createRendezvousClient(rendezvous.rpcPort(), EgressCodec.NONE);
        RendezvousContract clientB = createRendezvousClient(rendezvous.rpcPort(), EgressCodec.NONE);
        RendezvousContract clientC = createRendezvousClient(rendezvous.rpcPort(), EgressCodec.NONE);
        AdaptiveUdpPort attackerUdp = AdaptiveUdpPort.bindShared(new InetSocketAddress("127.0.0.1", 0));
        AdaptiveUdpPort targetUdp = AdaptiveUdpPort.bindShared(new InetSocketAddress("127.0.0.1", 0));
        PeerUdpPuncher puncher = new PeerUdpPuncher();
        LocalUdpBinding targetBinding = new LocalUdpBinding("t-udp", targetUdp, AddressFamily.IPV4, "127.0.0.1", CandidateType.LAN, false);
        puncher.attachToBinding(targetBinding, null);

        try {
            RendezvousAuthResult authA = clientA.authenticateNode("node-a", "secret-a", null, null);
            clientB.authenticateNode("node-b", "secret-b", null, Collections.singletonList("svc"));
            clientC.authenticateNode("node-c", "secret-c", null, null);

            // 1) Cross-tenant discovery & punch request must fail
            assertThrows(Exception.class, () -> clientC.discoverPeer("node-b"));
            assertThrows(Exception.class, () -> clientC.requestPunchSession("node-b", "svc"));

            // 2) Forged UDP observation packet (wrong key) must be dropped silently (0 replies, rejected increments)
            long beforeReplies = rendezvous.obsRepliesSent();
            byte[] wrongKey = new byte[32];
            Arrays.fill(wrongKey, (byte) 0x7A);
            byte[] nonce = new byte[UdpEndpointObserver.NONCE_BYTES];
            ByteBuf forgedObs = UdpEndpointObserver.encodeRequest(authA.getObsTokenId(), wrongKey, nonce, System.currentTimeMillis());
            attackerUdp.sendRawDatagram(Sockets.newLoopbackEndpoint(rendezvous.udpObservePort()), forgedObs).sync();
            waitFor(() -> rendezvous.obsRejectedCount() >= 1L, 3000, "forged OBS_REQ must be counted as rejected");
            assertEquals(beforeReplies, rendezvous.obsRepliesSent(), "forged OBS_REQ must trigger ZERO reflection replies");

            // 3) Forged UDP punch packet (wrong punchKey) must be dropped silently & never verify pair
            byte[] realPunchKey = new byte[32];
            Arrays.fill(realPunchKey, (byte) 0x33);
            EndpointCandidate fakePeer = new EndpointCandidate(AddressFamily.IPV4, "127.0.0.1", attackerUdp.port(),
                    Protocol.UDP, CandidateType.LAN, "fake", System.currentTimeMillis(), 60_000L, false);
            CompletableFuture<VerifiedUdpPath> punchF = puncher.startPunchSession(
                    9999L, realPunchKey, System.currentTimeMillis() + 600L,
                    Collections.singletonList(new UdpCandidatePair(targetBinding, fakePeer, fakePeer.toSocketAddress(), 100)),
                    1, 1, 100L, 500L, 4096L, 0L, null);
            ByteBuf forgedPunch = PeerUdpPuncher.encodePacket(
                    PeerUdpPuncher.TYPE_PROBE, (byte) 0, 9999L, 1, System.nanoTime(), 12345L, wrongKey);
            attackerUdp.sendRawDatagram(Sockets.newLoopbackEndpoint(targetUdp.port()), forgedPunch).sync();
            waitFor(() -> puncher.totalRejectedPackets() >= 1L, 3000, "forged punch packet must be rejected");
            assertNull(punchF.get(2, TimeUnit.SECONDS), "forged punch must NEVER mark pair verified");

            // 4) Node disable immediately revokes Rendezvous session & UDP observer token
            reg.setDisabled("node-a", true);
            waitFor(() -> rendezvous.authenticatedNodeCount() == 2, 3000, "revoking node-a must evict Rendezvous session");
            assertThrows(Exception.class, () -> clientA.heartbeat());

            // 5) Recent path cache generation invalidation & hysteresis check
            PathSelector selector = new PathSelector();
            assertFalse(selector.shouldSwitchUdpPath(200.0, 215.0), "delta < hysteresis must NOT flap");
            assertTrue(selector.shouldSwitchUdpPath(200.0, 230.0), "delta >= hysteresis should switch");
            // Verify recent cache generation mismatch invalidates entry
            assertNull(selector.getValidRecentHint("node-b", 1L, 1L, System.currentTimeMillis()));
            selector.invalidateTarget("node-b");
            selector.invalidateAll();
            assertEquals(0, selector.recentCacheSize());
        } finally {
            puncher.close();
            attackerUdp.close();
            targetUdp.close();
            clientA.close();
            clientB.close();
            clientC.close();
            rendezvous.close();
            reg.close();
        }
    }
}
