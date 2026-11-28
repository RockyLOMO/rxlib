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

    @Test
    @Timeout(30)
    void concurrentSessionsFromSameInitiatorBindIndependentlyWithoutCrossSessionOverwrite(@TempDir Path tempDir) throws Exception {
        NodeRegistry reg = openRegistry(tempDir);
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

        LocalAddress alphaLocal = new LocalAddress("b-alpha-" + System.nanoTime());
        LocalAddress betaLocal = new LocalAddress("b-beta-" + System.nanoTime());
        TransportConfig alphaConf = TransportConfig.dailyServer(0);
        alphaConf.setAuthSecret(E2E_SECRET);
        alphaConf.setEnableEncryption(true);
        TransportServer alphaServer = new TransportServer(alphaConf);
        alphaServer.setReceiveHandler((s, f) -> {
            String msg = f.toString(StandardCharsets.UTF_8);
            s.send(Unpooled.copiedBuffer("alpha:" + msg, StandardCharsets.UTF_8));
        });
        alphaServer.startLocal(alphaLocal);

        TransportConfig betaConf = TransportConfig.dailyServer(0);
        betaConf.setAuthSecret(E2E_SECRET);
        betaConf.setEnableEncryption(true);
        TransportServer betaServer = new TransportServer(betaConf);
        betaServer.setReceiveHandler((s, f) -> {
            String msg = f.toString(StandardCharsets.UTF_8);
            s.send(Unpooled.copiedBuffer("beta:" + msg, StandardCharsets.UTF_8));
        });
        betaServer.startLocal(betaLocal);

        AdaptiveUdpPort udpA = AdaptiveUdpPort.bindShared(new InetSocketAddress("127.0.0.1", 0));
        AdaptiveUdpPort udpB = AdaptiveUdpPort.bindShared(new InetSocketAddress("127.0.0.1", 0));
        UdpEndpointObserver.Client obsA = new UdpEndpointObserver.Client(udpA);
        UdpEndpointObserver.Client obsB = new UdpEndpointObserver.Client(udpB);
        PeerUdpPuncher puncherA = new PeerUdpPuncher();
        PeerUdpPuncher puncherB = new PeerUdpPuncher();
        LocalUdpBinding bindA = new LocalUdpBinding("a-udp", udpA, AddressFamily.IPV4, "127.0.0.1", CandidateType.LAN, false);
        LocalUdpBinding bindB = new LocalUdpBinding("b-udp", udpB, AddressFamily.IPV4, "127.0.0.1", CandidateType.LAN, false);
        puncherA.attachToBinding(bindA, obsA);
        puncherB.attachToBinding(bindB, obsB);

        RendezvousContract rvA = createRendezvousClient(rendezvous.rpcPort(), EgressCodec.NONE);
        RendezvousContract rvB = createRendezvousClient(rendezvous.rpcPort(), EgressCodec.NONE);
        RrpClient relayA = connectRawRelayClient(relayServer.listenPort(), "node-a", "secret-a");
        RrpClient relayB = connectRawRelayClient(relayServer.listenPort(), "node-b", "secret-b");
        relayB.registerRawService("svc-alpha", alphaLocal);
        relayB.registerRawService("svc-beta", betaLocal);

        PathSelector selectorA = new PathSelector();
        PathSelector selectorB = new PathSelector();
        PeerEndpointAcceptor acceptorB = new PeerEndpointAcceptor(selectorB, puncherB, () -> Collections.singletonList(bindB));
        acceptorB.bindTransportServer("svc-alpha", alphaServer);
        acceptorB.bindTransportServer("svc-beta", betaServer);
        acceptorB.attachToRendezvous(rvB);

        SelectedConnection c1 = null;
        SelectedConnection c2 = null;
        SelectedConnection c3 = null;
        try {
            RendezvousAuthResult authA = rvA.authenticateNode("node-a", "secret-a", null, null);
            RendezvousAuthResult authB = rvB.authenticateNode("node-b", "secret-b", null, Arrays.asList("svc-alpha", "svc-beta"));
            relayA.connectAsync().get(5, TimeUnit.SECONDS);
            relayB.connectAsync().get(5, TimeUnit.SECONDS);
            relayA.awaitRawAuthenticated().get(5, TimeUnit.SECONDS);
            relayB.awaitRawAuthenticated().get(5, TimeUnit.SECONDS);

            InetSocketAddress obsEp = Sockets.newLoopbackEndpoint(authA.getUdpObserverPort());
            obsA.observeAsync(obsEp, authA.getObsTokenId(), authA.getObsKey(), 3, 2000L).get(5, TimeUnit.SECONDS);
            obsB.observeAsync(obsEp, authB.getObsTokenId(), authB.getObsKey(), 3, 2000L).get(5, TimeUnit.SECONDS);
            PeerDiscoveryResult discB = rvA.discoverPeer("node-b");

            TransportConfig e2e = TransportConfig.dailyClient(null);
            e2e.setAuthSecret(E2E_SECRET);
            e2e.setEnableEncryption(true);

            ConnectRequest req1 = new ConnectRequest();
            req1.setLocalNodeId("node-a");
            req1.setTargetNodeId("node-b");
            req1.setTargetService("svc-alpha");
            req1.setLocalUdpBindings(Collections.singletonList(bindA));
            req1.setRemoteCandidates(discB.getCandidates());
            req1.setE2eTransportTemplate(e2e);
            req1.setRendezvousClient(rvA);
            req1.setRrpRelayClient(relayA);
            req1.setPuncher(puncherA);

            ConnectRequest req2 = new ConnectRequest();
            req2.setLocalNodeId("node-a");
            req2.setTargetNodeId("node-b");
            req2.setTargetService("svc-alpha");
            req2.setLocalUdpBindings(Collections.singletonList(bindA));
            req2.setRemoteCandidates(discB.getCandidates());
            req2.setE2eTransportTemplate(e2e);
            req2.setRendezvousClient(rvA);
            req2.setRrpRelayClient(relayA);
            req2.setPuncher(puncherA);

            ConnectRequest req3 = new ConnectRequest();
            req3.setLocalNodeId("node-a");
            req3.setTargetNodeId("node-b");
            req3.setTargetService("svc-beta");
            req3.setLocalUdpBindings(Collections.singletonList(bindA));
            req3.setRemoteCandidates(discB.getCandidates());
            req3.setE2eTransportTemplate(e2e);
            req3.setRendezvousClient(rvA);
            req3.setRrpRelayClient(relayA);
            req3.setPuncher(puncherA);

            // Launch 3 concurrent sessions from the SAME initiator node-a (2 to svc-alpha, 1 to svc-beta)
            CompletableFuture<SelectedConnection> f1 = selectorA.connectPeerAsync(req1);
            CompletableFuture<SelectedConnection> f2 = selectorA.connectPeerAsync(req2);
            CompletableFuture<SelectedConnection> f3 = selectorA.connectPeerAsync(req3);
            c1 = f1.get(10, TimeUnit.SECONDS);
            c2 = f2.get(10, TimeUnit.SECONDS);
            c3 = f3.get(10, TimeUnit.SECONDS);

            assertNotNull(c1.getUdpFuture().get(6, TimeUnit.SECONDS));
            assertNotNull(c2.getUdpFuture().get(6, TimeUnit.SECONDS));
            assertNotNull(c3.getUdpFuture().get(6, TimeUnit.SECONDS));
            assertNotEquals(c1.getPunchSessionId(), c2.getPunchSessionId());
            assertNotEquals(c1.getPunchSessionId(), c3.getPunchSessionId());

            final SelectedConnection fc1 = c1, fc2 = c2, fc3 = c3;
            waitFor(() -> fc1.session().isUsingUdp() && fc2.session().isUsingUdp() && fc3.session().isUsingUdp(),
                    5000, "all 3 concurrent sessions must independently promote to UDP");

            // Verify each session talks to its own target service with zero cross-binding
            CountDownLatch l1 = new CountDownLatch(1), l2 = new CountDownLatch(1), l3 = new CountDownLatch(1);
            AtomicReference<String> r1 = new AtomicReference<String>(), r2 = new AtomicReference<String>(), r3 = new AtomicReference<String>();
            c1.session().setReceiveHandler((s, f) -> { r1.set(f.toString(StandardCharsets.UTF_8)); l1.countDown(); });
            c2.session().setReceiveHandler((s, f) -> { r2.set(f.toString(StandardCharsets.UTF_8)); l2.countDown(); });
            c3.session().setReceiveHandler((s, f) -> { r3.set(f.toString(StandardCharsets.UTF_8)); l3.countDown(); });
            c1.session().send(Unpooled.copiedBuffer("one", StandardCharsets.UTF_8));
            c2.session().send(Unpooled.copiedBuffer("two", StandardCharsets.UTF_8));
            c3.session().send(Unpooled.copiedBuffer("three", StandardCharsets.UTF_8));
            assertTrue(l1.await(5, TimeUnit.SECONDS) && l2.await(5, TimeUnit.SECONDS) && l3.await(5, TimeUnit.SECONDS));
            assertEquals("alpha:one", r1.get());
            assertEquals("alpha:two", r2.get());
            assertEquals("beta:three", r3.get());

            // Verify UDP-first direct punch responder + service mismatch guard + revokeNode cleanup
            byte[] directKey = new byte[32];
            new SecureRandom().nextBytes(directKey);
            EndpointCandidate candA = new EndpointCandidate(AddressFamily.IPV4, "127.0.0.1", udpA.port(),
                    Protocol.UDP, CandidateType.LAN, "a", System.currentTimeMillis(), 60_000L, false);
            EndpointCandidate candB = new EndpointCandidate(AddressFamily.IPV4, "127.0.0.1", udpB.port(),
                    Protocol.UDP, CandidateType.LAN, "b", System.currentTimeMillis(), 60_000L, false);
            CompletableFuture<VerifiedUdpPath> respF = acceptorB.startDirectPunchResponder(
                    "node-a", "svc-alpha", 8888L, directKey, Collections.singletonList(candA));
            CompletableFuture<VerifiedUdpPath> initF = puncherA.startPunchSession(
                    8888L, directKey, System.currentTimeMillis() + 5000L,
                    Collections.singletonList(new UdpCandidatePair(bindA, candB, candB.toSocketAddress(), 100)),
                    3, 1, 50L, 3000L, 8192L, 0L, null);
            assertNotNull(respF.get(5, TimeUnit.SECONDS));
            assertNotNull(initF.get(5, TimeUnit.SECONDS));
            waitFor(() -> acceptorB.pendingVerifiedCount() == 1, 2000, "UDP-first verified binding must be buffered");

            // Revoking node-a must immediately clear its buffered bindings and cancel any active punches
            acceptorB.revokeNode("node-a");
            assertEquals(0, acceptorB.pendingVerifiedCount());
            assertEquals(0, acceptorB.pendingSessionCount());
        } finally {
            if (c1 != null) c1.close();
            if (c2 != null) c2.close();
            if (c3 != null) c3.close();
            acceptorB.close();
            puncherA.close();
            puncherB.close();
            udpA.close();
            udpB.close();
            rvA.close();
            rvB.close();
            relayA.close();
            relayB.close();
            alphaServer.close();
            betaServer.close();
            rendezvous.close();
            relayServer.close();
            reg.close();
        }
    }

    @Test
    @Timeout(25)
    void callerCancelAndRelayE2eFailureCleanUpAllInFlightResourcesWithZeroLeaks(@TempDir Path tempDir) throws Exception {
        NodeRegistry reg = openRegistry(tempDir);
        RrpConfig relayConf = new RrpConfig();
        relayConf.setToken(RELAY_TOKEN);
        relayConf.setBindPort(0);
        relayConf.setHeartbeatSeconds(0);
        RrpServer relayServer = new RrpServer(relayConf, reg);

        LocalAddress bLocal = new LocalAddress("b-cleanup-" + System.nanoTime());
        TransportConfig bConf = TransportConfig.pureTcpServer(0);
        bConf.setAuthSecret(E2E_SECRET);
        bConf.setEnableEncryption(true);
        TransportServer bServer = new TransportServer(bConf);
        bServer.startLocal(bLocal);

        TransportConfig directConf = TransportConfig.pureTcpServer(0);
        directConf.setAuthSecret(E2E_SECRET);
        directConf.setEnableEncryption(true);
        TransportServer directServer = new TransportServer(directConf);
        directServer.start();

        AdaptiveUdpPort udpA = AdaptiveUdpPort.bindShared(new InetSocketAddress("127.0.0.1", 0));
        PeerUdpPuncher puncherA = new PeerUdpPuncher();
        LocalUdpBinding bindA = new LocalUdpBinding("a-udp", udpA, AddressFamily.IPV4, "127.0.0.1", CandidateType.LAN, false);
        puncherA.attachToBinding(bindA, null);

        RrpClient relayA = connectRawRelayClient(relayServer.listenPort(), "node-a", "secret-a");
        RrpClient relayB = connectRawRelayClient(relayServer.listenPort(), "node-b", "secret-b");
        relayB.registerRawService("cleanup-svc", bLocal);

        try {
            relayA.connectAsync().get(5, TimeUnit.SECONDS);
            relayB.connectAsync().get(5, TimeUnit.SECONDS);
            relayA.awaitRawAuthenticated().get(5, TimeUnit.SECONDS);
            relayB.awaitRawAuthenticated().get(5, TimeUnit.SECONDS);

            int closedPort = allocateClosedPort();
            byte[] punchKey = new byte[32];
            new SecureRandom().nextBytes(punchKey);

            // Case 1: Caller cancels in-flight connectPeerAsync -> cancels UDP punch & does not leak Relay stream
            PathSelector sel1 = new PathSelector();
            sel1.getConfig().setDirectTcpBudgetMillis(4000L);
            sel1.getConfig().setUdpCheckBudgetMillis(4000L);
            TransportConfig e2eOk = TransportConfig.pureTcpClient(null);
            e2eOk.setAuthSecret(E2E_SECRET);
            e2eOk.setEnableEncryption(true);

            ConnectRequest cancelReq = new ConnectRequest();
            cancelReq.setLocalNodeId("node-a");
            cancelReq.setTargetNodeId("node-b");
            cancelReq.setTargetService("cleanup-svc");
            cancelReq.setLocalUdpBindings(Collections.singletonList(bindA));
            cancelReq.setRemoteCandidates(Arrays.asList(
                    new EndpointCandidate(AddressFamily.IPV4, "192.0.2.1", 44444, Protocol.TCP, CandidateType.HOST, "blackhole", System.currentTimeMillis(), 60_000L, false),
                    new EndpointCandidate(AddressFamily.IPV4, "127.0.0.1", closedPort, Protocol.UDP, CandidateType.LAN, "blackhole-udp", System.currentTimeMillis(), 60_000L, false)
            ));
            cancelReq.setE2eTransportTemplate(e2eOk);
            cancelReq.setRrpRelayClient(relayA);
            cancelReq.setPuncher(puncherA);
            cancelReq.setDirectPunchSessionId(5151L);
            cancelReq.setDirectPunchKey(punchKey);

            CompletableFuture<SelectedConnection> pendingF = sel1.connectPeerAsync(cancelReq);
            waitFor(() -> puncherA.activeSessionCount() == 1, 3000, "UDP punch session should start");
            assertTrue(pendingF.cancel(true));
            waitFor(() -> puncherA.activeSessionCount() == 0, 3000, "cancelling connectPeerAsync must cancel UDP punch");
            assertEquals(0, relayServer.activeRawStreamCount());
            assertEquals(0, relayA.activeRawCarrierCount());

            // Case 2: Relay opens carrier, then E2E handshake fails (wrong secret) -> RAW relay stream & carrier cleaned up!
            PathSelector sel2 = new PathSelector();
            TransportConfig badE2e = TransportConfig.pureTcpClient(null);
            badE2e.setAuthSecret("wrong-e2e-secret");
            badE2e.setEnableEncryption(true);
            badE2e.setHandshakeTimeoutMillis(1500);

            ConnectRequest failReq = new ConnectRequest();
            failReq.setLocalNodeId("node-a");
            failReq.setTargetNodeId("node-b");
            failReq.setTargetService("cleanup-svc");
            failReq.setRemoteCandidates(Collections.<EndpointCandidate>emptyList());
            failReq.setE2eTransportTemplate(badE2e);
            failReq.setRrpRelayClient(relayA);

            assertThrows(ExecutionException.class, () -> sel2.connectPeerAsync(failReq).get(8, TimeUnit.SECONDS));
            waitFor(() -> relayServer.activeRawStreamCount() == 0 && relayA.activeRawCarrierCount() == 0,
                    3000, "E2E handshake failure over Relay must immediately close RAW stream and carrier");

            // Case 3: SelectedConnection.close() cancels lingering UDP punch session
            PathSelector sel3 = new PathSelector();
            sel3.getConfig().setUdpCheckBudgetMillis(5000L);
            ConnectRequest lingerReq = new ConnectRequest();
            lingerReq.setLocalNodeId("node-a");
            lingerReq.setTargetNodeId("node-b");
            lingerReq.setTargetService("cleanup-svc");
            lingerReq.setLocalUdpBindings(Collections.singletonList(bindA));
            lingerReq.setRemoteCandidates(Arrays.asList(
                    new EndpointCandidate(AddressFamily.IPV4, "127.0.0.1", directServer.listenPort(), Protocol.TCP, CandidateType.LAN, "ok-tcp", System.currentTimeMillis(), 60_000L, false),
                    new EndpointCandidate(AddressFamily.IPV4, "127.0.0.1", closedPort, Protocol.UDP, CandidateType.LAN, "dead-udp", System.currentTimeMillis(), 60_000L, false)
            ));
            lingerReq.setE2eTransportTemplate(e2eOk);
            lingerReq.setPuncher(puncherA);
            lingerReq.setDirectPunchSessionId(6161L);
            lingerReq.setDirectPunchKey(punchKey);

            SelectedConnection conn = sel3.connectPeerAsync(lingerReq).get(5, TimeUnit.SECONDS);
            waitFor(() -> puncherA.activeSessionCount() == 1, 3000, "UDP punch should still be running");
            conn.close();
            waitFor(() -> puncherA.activeSessionCount() == 0, 3000, "SelectedConnection.close() must cancel lingering UDP punch");
        } finally {
            puncherA.close();
            udpA.close();
            relayA.close();
            relayB.close();
            directServer.close();
            bServer.close();
            relayServer.close();
            reg.close();
        }
    }

    @Test
    @Timeout(20)
    void rendezvousEnforcesTargetTenantServiceAuthAndInviteFailureRollback(@TempDir Path tempDir) throws Exception {
        NodeRegistry reg = openRegistry(tempDir);
        reg.enrollNode("node-a2", "tenant-1", "secret-a2", Collections.singleton("peer"), null);
        reg.enrollNode("node-b2", "tenant-1", "secret-b2", Collections.singleton("peer"), null);

        RendezvousConfig rvConf = new RendezvousConfig();
        rvConf.setAuthSecret(CONTROL_SECRET);
        rvConf.setRpcPort(0);
        rvConf.setUdpObservePort(0);
        rvConf.setMaxPunchSessionsPerNode(4);
        rvConf.setMaxPunchSessionsPerTarget(2);
        rvConf.setMaxPunchSessionsPerTenant(3);
        rvConf.setMaxGlobalPunchSessions(10);
        RendezvousService rendezvous = new RendezvousService(rvConf, reg);

        RendezvousContract clientA = createRendezvousClient(rendezvous.rpcPort(), EgressCodec.NONE);
        RendezvousContract clientA2 = createRendezvousClient(rendezvous.rpcPort(), EgressCodec.NONE);
        RendezvousContract clientB = createRendezvousClient(rendezvous.rpcPort(), EgressCodec.NONE);
        RendezvousContract clientB2 = createRendezvousClient(rendezvous.rpcPort(), EgressCodec.NONE);
        try {
            clientA.authenticateNode("node-a", "secret-a", null, null);
            clientA2.authenticateNode("node-a2", "secret-a2", null, null);
            clientB.authenticateNode("node-b", "secret-b", null, Collections.singletonList("svc-ok"));
            clientB2.authenticateNode("node-b2", "secret-b2", null, Collections.singletonList("svc-ok"));

            // Unauthorized target service must be rejected before allocating punch session
            assertThrows(Exception.class, () -> clientA.requestPunchSession("node-b", "svc-forbidden"));
            assertEquals(0, rendezvous.activePunchSessionCount());

            // Target cap = 2 (across different initiators)
            assertNotNull(clientA.requestPunchSessionForAttempt("node-b", "svc-ok", "att-1"));
            assertNotNull(clientA2.requestPunchSessionForAttempt("node-b", "svc-ok", "att-2"));
            assertEquals(2, rendezvous.activePunchSessionCount());
            assertThrows(Exception.class, () -> clientA.requestPunchSessionForAttempt("node-b", "svc-ok", "att-3"));

            // Tenant cap = 3 (1 more to node-b2 fills tenant-1 cap; next fails)
            assertNotNull(clientA.requestPunchSessionForAttempt("node-b2", "svc-ok", "att-4"));
            assertEquals(3, rendezvous.activePunchSessionCount());
            assertThrows(Exception.class, () -> clientA2.requestPunchSessionForAttempt("node-b2", "svc-ok", "att-5"));

            // Closing clients cleans up active sessions AND prunes zero-count counter map keys
            clientB.close();
            clientB2.close();
            waitFor(() -> rendezvous.activePunchSessionCount() == 0 && rendezvous.trackedCounterMapEntries() == 0,
                    4000, "disconnect must clean up punch sessions and prune zero-count map entries");
        } finally {
            clientA.close();
            clientA2.close();
            clientB.close();
            clientB2.close();
            rendezvous.close();
            reg.close();
        }
    }

    @Test
    @Timeout(15)
    void peerUdpPuncherEnforcesSessionDynamicCandidateNonceAndPairCaps() throws Exception {
        AdaptiveUdpPort targetUdp = AdaptiveUdpPort.bindShared(new InetSocketAddress("127.0.0.1", 0));
        AdaptiveUdpPort sender1 = AdaptiveUdpPort.bindShared(new InetSocketAddress("127.0.0.1", 0));
        AdaptiveUdpPort sender2 = AdaptiveUdpPort.bindShared(new InetSocketAddress("127.0.0.1", 0));
        AdaptiveUdpPort sender3 = AdaptiveUdpPort.bindShared(new InetSocketAddress("127.0.0.1", 0));
        AdaptiveUdpPort sender4 = AdaptiveUdpPort.bindShared(new InetSocketAddress("127.0.0.1", 0));
        PeerUdpPuncher puncher = new PeerUdpPuncher();
        puncher.setMaxActiveSessions(2);
        puncher.setMaxDynamicCandidatesPerSession(2);
        puncher.setMaxNoncesPerPair(4);
        puncher.setMaxTotalTrackedPairs(4);
        LocalUdpBinding bind = new LocalUdpBinding("t-udp", targetUdp, AddressFamily.IPV4, "127.0.0.1", CandidateType.LAN, false);
        puncher.attachToBinding(bind, null);

        try {
            byte[] key = new byte[32];
            Arrays.fill(key, (byte) 0x55);
            int deadPort = allocateClosedPort();
            EndpointCandidate dead = new EndpointCandidate(AddressFamily.IPV4, "127.0.0.1", deadPort,
                    Protocol.UDP, CandidateType.LAN, "dead", System.currentTimeMillis(), 60_000L, false);
            UdpCandidatePair pair = new UdpCandidatePair(bind, dead, dead.toSocketAddress(), 100);

            // Start 2 sessions (fills maxActiveSessions=2, trackedPairs=2)
            puncher.startPunchSession(101L, key, System.currentTimeMillis() + 5000L,
                    Collections.singletonList(pair), 1, 1, 200L, 4000L, 4096L, 0L, null);
            puncher.startPunchSession(102L, key, System.currentTimeMillis() + 5000L,
                    Collections.singletonList(pair), 1, 1, 200L, 4000L, 4096L, 0L, null);
            assertEquals(2, puncher.activeSessionCount());
            assertEquals(2, puncher.trackedPairCount());

            // 3rd session exceeds maxActiveSessions -> immediately completed with null
            CompletableFuture<VerifiedUdpPath> overflow = puncher.startPunchSession(103L, key, System.currentTimeMillis() + 5000L,
                    Collections.singletonList(pair), 1, 1, 200L, 4000L, 4096L, 0L, null);
            assertTrue(overflow.isDone());
            assertNull(overflow.get());
            assertEquals(2, puncher.activeSessionCount());

            // Send authenticated probes from 4 distinct ports -> only 2 dynamic candidates allowed (and capped by maxTotalTrackedPairs=4)
            InetSocketAddress dst = Sockets.newLoopbackEndpoint(targetUdp.port());
            for (AdaptiveUdpPort s : Arrays.asList(sender1, sender2, sender3, sender4)) {
                ByteBuf p = PeerUdpPuncher.encodePacket(PeerUdpPuncher.TYPE_PROBE, (byte) 0, 101L, 1, System.nanoTime(), s.port(), key);
                s.sendRawDatagram(dst, p).sync();
            }
            waitFor(() -> puncher.totalRejectedPackets() >= 2L, 3000,
                    "excess peer-reflexive dynamic candidates beyond cap must be rejected");
            assertTrue(puncher.trackedPairCount() <= 4, "trackedPairCount must stay within cap=4");

            puncher.cancelPunchSession(101L);
            puncher.cancelPunchSession(102L);
            assertEquals(0, puncher.activeSessionCount());
            assertEquals(0, puncher.trackedPairCount());
        } finally {
            puncher.close();
            targetUdp.close();
            sender1.close();
            sender2.close();
            sender3.close();
            sender4.close();
        }
    }

    @Test
    void multiNicTruncationFairnessAndWildcardUdpMappedPriorityBonus() {
        PathSelector.Config cfg = new PathSelector.Config();
        cfg.setMaxTcpTargetsPerSession(4);
        cfg.setMaxUdpPairsPerSession(4);
        PathSelector selector = new PathSelector(cfg);
        long now = 200_000L;

        // TCP fairness: 2 NICs x 4 HOST remotes (equal score 170) truncated to 4 -> must pick 2 from nic-1 and 2 from nic-2!
        List<LocalTcpBinding> tcpLocals = Arrays.asList(
                new LocalTcpBinding("nic-1", AddressFamily.IPV4, "192.168.1.10", CandidateType.LAN, false),
                new LocalTcpBinding("nic-2", AddressFamily.IPV4, "10.0.0.10", CandidateType.LAN, false)
        );
        List<EndpointCandidate> tcpRemotes = Arrays.asList(
                new EndpointCandidate(AddressFamily.IPV4, "203.0.113.1", 8001, Protocol.TCP, CandidateType.HOST, "b", now, 60_000L, false),
                new EndpointCandidate(AddressFamily.IPV4, "203.0.113.2", 8002, Protocol.TCP, CandidateType.HOST, "b", now, 60_000L, false),
                new EndpointCandidate(AddressFamily.IPV4, "203.0.113.3", 8003, Protocol.TCP, CandidateType.HOST, "b", now, 60_000L, false),
                new EndpointCandidate(AddressFamily.IPV4, "203.0.113.4", 8004, Protocol.TCP, CandidateType.HOST, "b", now, 60_000L, false)
        );
        CandidatePlan<TcpCandidateTarget> tcpPlan = selector.planDirectTcpTargets(tcpLocals, tcpRemotes, null, now);
        assertEquals(4, tcpPlan.getScheduled().size());
        long nic1Tcp = tcpPlan.getScheduled().stream().filter(t -> "nic-1".equals(t.getLocalBinding().getBindingId())).count();
        long nic2Tcp = tcpPlan.getScheduled().stream().filter(t -> "nic-2".equals(t.getLocalBinding().getBindingId())).count();
        assertEquals(2L, nic1Tcp, "nic-1 should get fair share");
        assertEquals(2L, nic2Tcp, "nic-2 must not be starved under budget truncation");

        // UDP fairness & wildcard UDP_MAPPED priority bonus (155 vs 120)
        AdaptiveUdpPort p1 = AdaptiveUdpPort.bindShared(new InetSocketAddress("127.0.0.1", 0));
        AdaptiveUdpPort p2 = AdaptiveUdpPort.bindShared(new InetSocketAddress("127.0.0.1", 0));
        try {
            LocalUdpBinding specific = new LocalUdpBinding("nic-specific", p1, AddressFamily.IPV4, "192.168.1.10", CandidateType.LAN, false);
            LocalUdpBinding wildcard = new LocalUdpBinding("nic-wildcard", p2, AddressFamily.IPV4, "0.0.0.0", CandidateType.HOST, true);
            EndpointCandidate mapped = new EndpointCandidate(AddressFamily.IPV4, "198.51.100.9", 41234, Protocol.UDP, CandidateType.UDP_MAPPED, "obs", now, 60_000L, true);
            CandidatePlan<UdpCandidatePair> mappedPlan = selector.planUdpPairs(Arrays.asList(specific, wildcard), Collections.singletonList(mapped), null, now);
            assertEquals(2, mappedPlan.getScheduled().size());
            assertEquals("nic-wildcard", mappedPlan.getScheduled().get(0).getLocal().getBindingId(),
                    "wildcard socket must rank ahead of non-wildcard socket for UDP_MAPPED remote");
            assertTrue(mappedPlan.getScheduled().get(0).getPriorityScore() > mappedPlan.getScheduled().get(1).getPriorityScore());

            List<EndpointCandidate> udpRemotes = Arrays.asList(
                    new EndpointCandidate(AddressFamily.IPV4, "203.0.113.1", 9001, Protocol.UDP, CandidateType.HOST, "b", now, 60_000L, false),
                    new EndpointCandidate(AddressFamily.IPV4, "203.0.113.2", 9002, Protocol.UDP, CandidateType.HOST, "b", now, 60_000L, false),
                    new EndpointCandidate(AddressFamily.IPV4, "203.0.113.3", 9003, Protocol.UDP, CandidateType.HOST, "b", now, 60_000L, false),
                    new EndpointCandidate(AddressFamily.IPV4, "203.0.113.4", 9004, Protocol.UDP, CandidateType.HOST, "b", now, 60_000L, false)
            );
            CandidatePlan<UdpCandidatePair> udpPlan = selector.planUdpPairs(Arrays.asList(specific, wildcard), udpRemotes, null, now);
            assertEquals(4, udpPlan.getScheduled().size());
            long specUdp = udpPlan.getScheduled().stream().filter(p -> "nic-specific".equals(p.getLocal().getBindingId())).count();
            long wildUdp = udpPlan.getScheduled().stream().filter(p -> "nic-wildcard".equals(p.getLocal().getBindingId())).count();
            assertEquals(2L, specUdp);
            assertEquals(2L, wildUdp);
        } finally {
            p1.close();
            p2.close();
        }
    }
}
