package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.local.LocalAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.rx.net.EgressCodec;
import org.rx.net.EgressConfig;
import org.rx.net.registry.NodeRegistry;
import org.rx.net.registry.NodeRegistryConfig;
import org.rx.net.rpc.Remoting;
import org.rx.net.rpc.RpcClientConfig;
import org.rx.net.rpc.RpcServerConfig;
import org.rx.net.transport.core.TransportClient;
import org.rx.net.transport.core.TransportConfig;
import org.rx.net.transport.core.TransportServer;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end verification for {@link NodeRegistry}-authorized RRP RAW logical stream Relay:
 * bidirectional streaming, half-close ordering, end-to-end AEAD + zlib3 Transport Core v2 and
 * Remoting RPC over virtual {@link LocalAddress} carriers, ACL/tenant/revocation enforcement,
 * multi-tier stream &amp; pending-byte budgets, and leak-free disconnect teardown.
 */
class RrpRawRelayTest {
    public interface RawRelayRpcContract extends AutoCloseable {
        String echo(String input);

        int sum(int a, int b);

        @Override
        default void close() {}
    }

    private NodeRegistry openRegistry(Path tempDir) {
        NodeRegistryConfig rc = new NodeRegistryConfig();
        rc.setEnabled(true);
        rc.setDbPath(tempDir.resolve("rrp-raw-reg-" + System.nanoTime()).toString());
        rc.setPbkdf2Iterations(1000);
        rc.setFlushPeriodMillis(0L);
        rc.setTtlSweepPeriodMillis(0L);
        NodeRegistry reg = new NodeRegistry(rc);
        reg.enrollNode("node-a", "tenant-1", "secret-a", Collections.singleton("peer"), null);
        reg.enrollNode("node-b", "tenant-1", "secret-b", Collections.singleton("peer"), null);
        reg.enrollNode("node-c", "tenant-2", "secret-c", Collections.singleton("peer"), null);
        return reg;
    }

    private RrpClient connectRawClient(int relayPort, String relayToken, EgressCodec codec,
                                       String nodeId, String nodeSecret) throws Exception {
        RrpConfig conf = new RrpConfig();
        conf.setToken(relayToken);
        conf.setServerEndpoint("127.0.0.1:" + relayPort);
        conf.setEgressConfig(EgressConfig.client(codec));
        conf.setEnableReconnect(false);
        conf.setHeartbeatSeconds(0);
        conf.setNodeId(nodeId);
        conf.setNodeSecret(nodeSecret);
        return new RrpClient(conf);
    }

    @Test
    @Timeout(20)
    void dualNatRawStreamBidirectionalMultiChunkAndHalfCloseOrdering(@TempDir Path tempDir) throws Exception {
        NodeRegistry reg = openRegistry(tempDir);
        RrpConfig sConf = new RrpConfig();
        sConf.setToken("relay-tok");
        sConf.setBindPort(0);
        sConf.setHeartbeatSeconds(0);
        sConf.setMaxRawFrameBytes(4096);
        RrpServer server = new RrpServer(sConf, reg);
        int port = server.listenPort();

        RrpClient clientA = connectRawClient(port, "relay-tok", EgressCodec.HTTP, "node-a", "secret-a");
        RrpClient clientB = connectRawClient(port, "relay-tok", EgressCodec.ZLIB_STORED, "node-b", "secret-b");
        clientA.config.setMaxRawFrameBytes(4096);
        clientB.config.setMaxRawFrameBytes(4096);

        ByteArrayOutputStream bReceived = new ByteArrayOutputStream();
        CountDownLatch bHalfClosed = new CountDownLatch(1);
        ByteArrayOutputStream aReceived = new ByteArrayOutputStream();
        CountDownLatch aHalfClosed = new CountDownLatch(1);

        clientB.registerRawStreamAcceptor("raw-echo", (streamId, initiatorNode) -> {
            assertEquals("node-a", initiatorNode);
            return new RrpClient.RawStreamListener() {
                @Override
                public void onData(long id, ByteBuf data) {
                    byte[] chunk = new byte[data.readableBytes()];
                    data.readBytes(chunk);
                    synchronized (bReceived) {
                        bReceived.write(chunk, 0, chunk.length);
                    }
                    // Echo back chunk immediately
                    clientB.sendRawData(id, Unpooled.wrappedBuffer(chunk));
                }

                @Override
                public void onHalfClose(long id) {
                    bHalfClosed.countDown();
                    clientB.halfCloseRawStream(id);
                }
            };
        });

        try {
            clientA.connectAsync().get(5, TimeUnit.SECONDS);
            clientB.connectAsync().get(5, TimeUnit.SECONDS);
            clientA.awaitRawAuthenticated().get(5, TimeUnit.SECONDS);
            clientB.awaitRawAuthenticated().get(5, TimeUnit.SECONDS);

            long streamId = clientA.openRawStreamAsync("node-b", "raw-echo", new RrpClient.RawStreamListener() {
                @Override
                public void onData(long id, ByteBuf data) {
                    byte[] chunk = new byte[data.readableBytes()];
                    data.readBytes(chunk);
                    synchronized (aReceived) {
                        aReceived.write(chunk, 0, chunk.length);
                    }
                }

                @Override
                public void onHalfClose(long id) {
                    aHalfClosed.countDown();
                }
            }).get(5, TimeUnit.SECONDS);

            byte[] payload = new byte[96 * 1024];
            new Random(42L).nextBytes(payload);
            clientA.sendRawData(streamId, Unpooled.wrappedBuffer(payload)).sync();
            clientA.halfCloseRawStream(streamId).sync();

            assertTrue(bHalfClosed.await(8, TimeUnit.SECONDS), "B should receive half-close after all bytes");
            assertTrue(aHalfClosed.await(8, TimeUnit.SECONDS), "A should receive half-close after echo");
            assertArrayEquals(payload, bReceived.toByteArray(), "B must receive exact bytes before half-close");
            assertArrayEquals(payload, aReceived.toByteArray(), "A must receive exact echoed bytes before half-close");
            assertEquals(1L, server.rawStreamsOpenedCount());
            assertTrue(server.rawRelayIngressBytes() >= payload.length * 2L);
            assertTrue(server.rawRelayEgressBytes() >= payload.length * 2L);

            RrpIntegrationTest.waitFor(() -> server.activeRawStreamCount() == 0 && server.globalRelayPendingBytes() == 0L,
                    5000, "stream and pending bytes must return to 0 after bidirectional half-close");
        } finally {
            clientA.close();
            clientB.close();
            server.close();
            reg.close();
        }
    }

    @Test
    @Timeout(25)
    void endToEndAeadTransportCoreAndRemotingRpcOverRawRelayCarrier(@TempDir Path tempDir) throws Exception {
        NodeRegistry reg = openRegistry(tempDir);
        RrpConfig sConf = new RrpConfig();
        sConf.setToken("relay-tok");
        sConf.setBindPort(0);
        sConf.setHeartbeatSeconds(0);
        RrpServer relayServer = new RrpServer(sConf, reg);
        int port = relayServer.listenPort();

        // B hosts: (1) E2E AEAD + zlib3 + deferred-adaptive TransportServer on LocalAddress
        LocalAddress bTransportLocal = new LocalAddress("b-e2e-tc-" + System.nanoTime());
        TransportConfig bTcConf = TransportConfig.dailyServer(0);
        bTcConf.setAuthSecret("e2e-peer-secret-ab");
        bTcConf.setEnableEncryption(true);
        bTcConf.setEnableCompression(true);
        bTcConf.setCompressionLevel(3);
        TransportServer bTransportServer = new TransportServer(bTcConf);
        bTransportServer.setReceiveHandler((session, frame) -> session.send(frame.retainedDuplicate()));
        bTransportServer.startLocal(bTransportLocal);

        // B hosts: (2) E2E AEAD Remoting RPC service on LocalAddress
        LocalAddress bRpcLocal = new LocalAddress("b-e2e-rpc-" + System.nanoTime());
        TransportConfig bRpcTc = TransportConfig.pureTcpServer(0);
        bRpcTc.setAuthSecret("e2e-rpc-secret-ab");
        bRpcTc.setEnableEncryption(true);
        bRpcTc.setEnableCompression(true);
        RawRelayRpcContract rpcImpl = new RawRelayRpcContract() {
            @Override
            public String echo(String input) {
                return "rpc-b:" + input;
            }

            @Override
            public int sum(int a, int b) {
                return a + b;
            }
        };
        TransportServer bRpcServer = Remoting.register(rpcImpl, RpcServerConfig.localCarrier(bRpcLocal, bRpcTc));

        RrpClient clientA = connectRawClient(port, "relay-tok", EgressCodec.HTTP, "node-a", "secret-a");
        RrpClient clientB = connectRawClient(port, "relay-tok", EgressCodec.HTTP, "node-b", "secret-b");
        clientB.registerRawService("tc-echo", bTransportLocal);
        clientB.registerRawService("rpc-svc", bRpcLocal);

        TransportClient e2eClient = null;
        RawRelayRpcContract rpcFacade = null;
        try {
            clientA.connectAsync().get(5, TimeUnit.SECONDS);
            clientB.connectAsync().get(5, TimeUnit.SECONDS);
            clientA.awaitRawAuthenticated().get(5, TimeUnit.SECONDS);
            clientB.awaitRawAuthenticated().get(5, TimeUnit.SECONDS);

            // 1) E2E AEAD + zlib3 + deferred-adaptive Transport Core v2 over RRP RAW Relay carrier
            LocalAddress tcCarrier = clientA.openRawCarrierAsync("node-b", "tc-echo").get(5, TimeUnit.SECONDS);
            TransportConfig aTcConf = TransportConfig.dailyClient(null);
            aTcConf.setAuthSecret("e2e-peer-secret-ab");
            aTcConf.setEnableEncryption(true);
            aTcConf.setEnableCompression(true);
            aTcConf.setCompressionLevel(3);
            e2eClient = new TransportClient(aTcConf);

            CountDownLatch echoLatch = new CountDownLatch(1);
            AtomicReference<byte[]> echoed = new AtomicReference<byte[]>();
            e2eClient.setReceiveHandler((session, frame) -> {
                byte[] bytes = new byte[frame.readableBytes()];
                frame.readBytes(bytes);
                echoed.set(bytes);
                echoLatch.countDown();
            });
            e2eClient.connectLocal(tcCarrier);

            assertTrue(e2eClient.session().isEncrypted(), "E2E Transport Core over Relay must be AEAD-encrypted");
            assertTrue(e2eClient.session().isCompressed(), "E2E Transport Core over Relay must negotiate zlib3");
            assertTrue(e2eClient.session().isAdaptiveUdpNegotiated(), "Deferred adaptive UDP must negotiate over LocalChannel carrier");
            assertNotNull(e2eClient.session().adaptiveHandler(), "Deferred AdaptiveTcpHandler must be installed");
            assertNotNull(e2eClient.session().adaptiveUdpKey(), "Per-session adaptive UDP key must be derived");

            byte[] payload = new byte[128 * 1024];
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) (i % 251);
            }
            e2eClient.send(Unpooled.wrappedBuffer(payload));
            assertTrue(echoLatch.await(8, TimeUnit.SECONDS), "E2E AEAD echo over Relay must complete");
            assertArrayEquals(payload, echoed.get());

            // 2) Full Remoting RPC over RRP RAW Relay carrier (zero Fury deserialization on Relay!)
            LocalAddress rpcCarrier = clientA.openRawCarrierAsync("node-b", "rpc-svc").get(5, TimeUnit.SECONDS);
            TransportConfig aRpcTc = TransportConfig.pureTcpClient(null);
            aRpcTc.setAuthSecret("e2e-rpc-secret-ab");
            aRpcTc.setEnableEncryption(true);
            aRpcTc.setEnableCompression(true);
            rpcFacade = Remoting.createFacade(RawRelayRpcContract.class, RpcClientConfig.localCarrier(rpcCarrier, aRpcTc));

            assertEquals("rpc-b:hello-dual-nat", rpcFacade.echo("hello-dual-nat"));
            assertEquals(42, rpcFacade.sum(19, 23));
            assertEquals(2L, relayServer.rawStreamsOpenedCount());
            assertTrue(relayServer.rawRelayIngressBytes() > 0L);
            assertTrue(relayServer.rawRelayEgressBytes() > 0L);

            // Close E2E clients and verify Relay stream count & pending bytes drop to 0
            e2eClient.close();
            e2eClient = null;
            ((AutoCloseable) rpcFacade).close();
            rpcFacade = null;

            RrpIntegrationTest.waitFor(() -> relayServer.activeRawStreamCount() == 0
                    && relayServer.globalRelayPendingBytes() == 0L,
                    5000, "all Relay RAW streams must clean up when E2E LocalChannel clients close");
        } finally {
            if (e2eClient != null) e2eClient.close();
            if (rpcFacade instanceof AutoCloseable) {
                try { ((AutoCloseable) rpcFacade).close(); } catch (Exception ignore) {}
            }
            clientA.close();
            clientB.close();
            bTransportServer.close();
            bRpcServer.close();
            relayServer.close();
            reg.close();
        }
    }

    @Test
    @Timeout(20)
    void securityUnauthorizedCrossTenantWrongSecretAndRevocationTeardown(@TempDir Path tempDir) throws Exception {
        NodeRegistry reg = openRegistry(tempDir);
        RrpConfig sConf = new RrpConfig();
        sConf.setToken("relay-tok");
        sConf.setBindPort(0);
        sConf.setHeartbeatSeconds(0);
        RrpServer server = new RrpServer(sConf, reg);
        int port = server.listenPort();

        RrpClient clientA = connectRawClient(port, "relay-tok", EgressCodec.NONE, "node-a", "secret-a");
        RrpClient clientB = connectRawClient(port, "relay-tok", EgressCodec.NONE, "node-b", "secret-b");
        RrpClient clientC = connectRawClient(port, "relay-tok", EgressCodec.NONE, "node-c", "secret-c");
        RrpClient badClient = connectRawClient(port, "relay-tok", EgressCodec.NONE, "node-a", "wrong-secret");

        clientB.registerRawStreamAcceptor("svc-1", (id, init) -> (sId, data) -> {});

        try {
            // Wrong secret must be rejected
            badClient.connectAsync().get(5, TimeUnit.SECONDS);
            ExecutionException badAuth = assertThrows(ExecutionException.class,
                    () -> badClient.awaitRawAuthenticated().get(5, TimeUnit.SECONDS));
            assertInstanceOf(SecurityException.class, badAuth.getCause());
            badClient.close();

            clientA.connectAsync().get(5, TimeUnit.SECONDS);
            clientB.connectAsync().get(5, TimeUnit.SECONDS);
            clientC.connectAsync().get(5, TimeUnit.SECONDS);
            clientA.awaitRawAuthenticated().get(5, TimeUnit.SECONDS);
            clientB.awaitRawAuthenticated().get(5, TimeUnit.SECONDS);
            clientC.awaitRawAuthenticated().get(5, TimeUnit.SECONDS);

            // Cross-tenant C (tenant-2) -> B (tenant-1) must be rejected
            ExecutionException crossTenant = assertThrows(ExecutionException.class,
                    () -> clientC.openRawStreamAsync("node-b", "svc-1", (id, data) -> {}).get(5, TimeUnit.SECONDS));
            assertInstanceOf(SecurityException.class, crossTenant.getCause());
            assertEquals(0L, server.rawStreamsOpenedCount());

            // Unknown service must be rejected (no arbitrary port/target forwarding)
            ExecutionException unknownSvc = assertThrows(ExecutionException.class,
                    () -> clientA.openRawStreamAsync("node-b", "127.0.0.1:22", (id, data) -> {}).get(5, TimeUnit.SECONDS));
            assertTrue(unknownSvc.getCause().getMessage().contains("service not hosted"));
            assertEquals(0L, server.rawStreamsOpenedCount());

            // Open authorized A -> B stream, then disable node-a in NodeRegistry -> immediate teardown!
            CountDownLatch bClosedLatch = new CountDownLatch(1);
            AtomicInteger bCloseReason = new AtomicInteger(-1);
            clientB.registerRawStreamAcceptor("svc-1", (id, init) -> new RrpClient.RawStreamListener() {
                @Override public void onData(long sId, ByteBuf data) {}
                @Override public void onClose(long sId, byte reason) {
                    bCloseReason.set(reason);
                    bClosedLatch.countDown();
                }
            });

            long streamId = clientA.openRawStreamAsync("node-b", "svc-1", (id, data) -> {}).get(5, TimeUnit.SECONDS);
            assertTrue(streamId > 0L);
            assertEquals(1, server.activeRawStreamCount());

            reg.setDisabled("node-a", true);
            assertTrue(bClosedLatch.await(5, TimeUnit.SECONDS), "revoking node-a must tear down active stream on B");
            assertEquals(RrpConfig.RAW_CLOSE_REVOKED, bCloseReason.get());
            RrpIntegrationTest.waitFor(() -> server.activeRawStreamCount() == 0,
                    5000, "active raw streams must drop to 0 after revocation");
        } finally {
            badClient.close();
            clientA.close();
            clientB.close();
            clientC.close();
            server.close();
            reg.close();
        }
    }

    @Test
    @Timeout(20)
    void multiTierStreamCapAndOverflowFailFastIsolatesSlowStream(@TempDir Path tempDir) throws Exception {
        NodeRegistry reg = openRegistry(tempDir);
        RrpConfig sConf = new RrpConfig();
        sConf.setToken("relay-tok");
        sConf.setBindPort(0);
        sConf.setHeartbeatSeconds(0);
        sConf.setMaxRawStreamsPerNode(2);
        sConf.setMaxStreamRelayPendingBytes(4096);
        sConf.setStreamPauseHighWaterBytes(2048);
        sConf.setStreamResumeLowWaterBytes(1024);
        RrpServer server = new RrpServer(sConf, reg);
        int port = server.listenPort();

        RrpClient clientA = connectRawClient(port, "relay-tok", EgressCodec.NONE, "node-a", "secret-a");
        RrpClient clientB = connectRawClient(port, "relay-tok", EgressCodec.NONE, "node-b", "secret-b");

        AtomicReference<CountDownLatch> healthyMsgLatch = new AtomicReference<CountDownLatch>(new CountDownLatch(1));
        AtomicReference<String> healthyReceived = new AtomicReference<String>();
        clientB.registerRawStreamAcceptor("svc", (id, init) -> (sId, data) -> {
            byte[] b = new byte[data.readableBytes()];
            data.readBytes(b);
            String txt = new String(b, StandardCharsets.UTF_8);
            if (txt.equals("healthy-ping")) {
                healthyReceived.set(txt);
                healthyMsgLatch.get().countDown();
            }
        });

        try {
            clientA.connectAsync().get(5, TimeUnit.SECONDS);
            clientB.connectAsync().get(5, TimeUnit.SECONDS);
            clientA.awaitRawAuthenticated().get(5, TimeUnit.SECONDS);
            clientB.awaitRawAuthenticated().get(5, TimeUnit.SECONDS);

            AtomicBoolean s1Closed = new AtomicBoolean();
            CountDownLatch s1ClosedLatch = new CountDownLatch(1);
            long s1 = clientA.openRawStreamAsync("node-b", "svc", new RrpClient.RawStreamListener() {
                @Override public void onData(long streamId, ByteBuf data) {}
                @Override public void onClose(long streamId, byte reason) {
                    s1Closed.set(true);
                    s1ClosedLatch.countDown();
                }
            }).get(5, TimeUnit.SECONDS);
            long s2 = clientA.openRawStreamAsync("node-b", "svc", (id, data) -> {}).get(5, TimeUnit.SECONDS);

            // 3rd stream exceeds maxRawStreamsPerNode=2
            ExecutionException capEx = assertThrows(ExecutionException.class,
                    () -> clientA.openRawStreamAsync("node-b", "svc", (id, data) -> {}).get(5, TimeUnit.SECONDS));
            assertTrue(capEx.getCause().getMessage().contains("budget exceeded"));

            // Send > maxStreamRelayPendingBytes (4096) in one chunk on s1 -> must overflow & close ONLY s1!
            byte[] oversizeForStreamCap = new byte[5000];
            clientA.sendRawData(s1, Unpooled.wrappedBuffer(oversizeForStreamCap));
            assertTrue(s1ClosedLatch.await(5, TimeUnit.SECONDS), "overflowing stream s1 must fail-fast close");
            assertTrue(s1Closed.get());
            assertTrue(server.rawStreamOverflowCloses() >= 1L);

            // Healthy stream s2 on the exact same TCP connection must stay alive and deliver data!
            clientA.sendRawData(s2, Unpooled.wrappedBuffer("healthy-ping".getBytes(StandardCharsets.UTF_8)));
            assertTrue(healthyMsgLatch.get().await(5, TimeUnit.SECONDS), "healthy stream s2 must not be harmed by s1 overflow");
            assertEquals("healthy-ping", healthyReceived.get());

            // Disconnect B -> s2 closes and server counters drop to 0
            clientB.close();
            RrpIntegrationTest.waitFor(() -> server.activeRawStreamCount() == 0
                    && server.globalRelayPendingBytes() == 0L
                    && reg.getOnlineNode("node-b") == null,
                    5000, "disconnecting B must clean up all streams, pending bytes, and online node");
        } finally {
            clientA.close();
            clientB.close();
            server.close();
            reg.close();
        }
    }

    @Test
    void rawRelayRejectsEmptyTokenEvenWhenAllowUnauthenticatedLocalIsSet(@TempDir Path tempDir) {
        NodeRegistryConfig regCfg = new NodeRegistryConfig();
        regCfg.setEnabled(true);
        regCfg.setDbPath(tempDir.resolve("rrp-empty-tok").toAbsolutePath().toString());
        regCfg.setFlushPeriodMillis(0L);
        regCfg.setTtlSweepPeriodMillis(0L);
        regCfg.setPbkdf2Iterations(1000);
        NodeRegistry reg = new NodeRegistry(regCfg);
        try {
            RrpConfig badCfg = new RrpConfig();
            badCfg.setBindPort(0);
            badCfg.setEnableRawRelay(true);
            badCfg.setAllowUnauthenticatedLocal(true);
            assertThrows(IllegalArgumentException.class, () -> new RrpServer(badCfg, reg));
        } finally {
            reg.close();
        }
    }
}
