package org.rx.util.rss;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.EgressCodec;
import org.rx.net.EgressConfig;
import org.rx.net.Sockets;
import org.rx.net.nameserver.Nameserver;
import org.rx.net.registry.NodeRegistry;
import org.rx.net.registry.NodeRegistryConfig;
import org.rx.net.rendezvous.RendezvousContract;
import org.rx.net.rpc.Remoting;
import org.rx.net.rpc.RpcClientConfig;
import org.rx.net.socks.RrpClient;
import org.rx.net.socks.RrpConfig;
import org.rx.net.transport.core.TransportConfig;

import java.io.File;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@link RssRuntime} opt-in lifecycle for shared {@link NodeRegistry} + {@link org.rx.net.rendezvous.RendezvousService}
 * + {@link org.rx.net.socks.RrpServer} RAW Relay:
 * <ul>
 *     <li>Default disabled creates zero H2 DB files, zero Rendezvous/Relay listeners.</li>
 *     <li>Config validation forbids unauthenticated public NodeService, missing NodeRegistry dependency, and port collisions.</li>
 *     <li>Enabled mode shares ONE {@link NodeRegistry} across Nameserver, RendezvousService, and RrpServer Relay.</li>
 *     <li>Transactional hot-reload reuses the same NodeRegistry, rejects live dbPath mutation without harming runtime, rolls back on hook failure, and cleanly shuts down in reverse order.</li>
 * </ul>
 */
class RssRuntimeNodeServiceIntegrationTest {

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private RssClientConf baseConf(Path tempDir) throws Exception {
        RssClientConf conf = new RssClientConf();
        conf.socksPwd = "test-socks-pwd";
        conf.socksBindPort = false;
        conf.shadowDnsPort = freePort();
        conf.nameserver.setRegisterPort(freePort());
        conf.nameserver.setDnsPort(conf.shadowDnsPort);

        RssClientConf.SocksServer upstream = new RssClientConf.SocksServer(
                "up-1", 1, new AuthenticEndpoint(Sockets.newLoopbackEndpoint(freePort()), "u", "p"));
        upstream.type = RssClientConf.UpstreamType.SOCKS5;
        conf.socksServers = Collections.singletonList(upstream);

        ShadowUser user = new ShadowUser();
        user.setUsername("u1");
        user.setSsPort(freePort());
        user.setSsPwd("ss-pwd-1");
        user.setSocksUser("u1");
        conf.shadowUsers = Collections.singletonList(user);

        conf.nodeRegistry = new NodeRegistryConfig();
        conf.nodeRegistry.setEnabled(false);
        conf.nodeRegistry.setDbPath(tempDir.resolve("node-reg-default").toString());
        conf.nodeRegistry.setPbkdf2Iterations(1000);
        conf.nodeRegistry.setFlushPeriodMillis(0L);
        conf.nodeRegistry.setTtlSweepPeriodMillis(0L);

        conf.nodeService = new RssClientConf.NodeServiceConf();
        conf.nodeService.enabled = false;
        return conf;
    }

    @Test
    void validationRejectsUnauthenticatedMissingDependencyAndPortCollisions(@TempDir Path tempDir) throws Exception {
        RssClientConf conf = baseConf(tempDir);
        assertTrue(RssClient.normalizeAndValidateRssConfig(conf));

        // 1) nodeService enabled without nodeRegistry enabled -> fail
        conf.nodeService.enabled = true;
        conf.nodeService.rendezvousEnabled = true;
        conf.nodeService.rendezvousPort = freePort();
        conf.nodeService.udpObservePort = freePort();
        conf.nodeService.authToken = "strong-tok";
        assertFalse(RssClient.normalizeAndValidateRssConfig(conf), "must require nodeRegistry.enabled=true");

        // 2) nodeRegistry enabled, but empty authToken -> fail
        conf.nodeRegistry.setEnabled(true);
        conf.nodeService.authToken = "";
        assertFalse(RssClient.normalizeAndValidateRssConfig(conf), "must forbid unauthenticated public nodeService");

        // 3) rendezvousPort collides with nameserver.registerPort -> fail
        conf.nodeService.authToken = "strong-tok";
        conf.nodeService.rendezvousPort = conf.nameserver.getRegisterPort();
        assertFalse(RssClient.normalizeAndValidateRssConfig(conf), "must reject TCP port collision");

        // 4) udpObservePort collides with shadowDnsPort -> fail
        conf.nodeService.rendezvousPort = freePort();
        conf.nodeService.udpObservePort = conf.shadowDnsPort;
        assertFalse(RssClient.normalizeAndValidateRssConfig(conf), "must reject UDP port collision");

        // 5) relayPort collides with ShadowUser ssPort -> fail
        conf.nodeService.udpObservePort = freePort();
        conf.nodeService.relayEnabled = true;
        conf.nodeService.relayPort = conf.shadowUsers.get(0).getSsPort();
        assertFalse(RssClient.normalizeAndValidateRssConfig(conf), "must reject collision with ssPort");

        // 6) valid non-colliding config passes
        conf.nodeService.relayPort = freePort();
        assertTrue(RssClient.normalizeAndValidateRssConfig(conf));
        assertFalse(conf.nodeService.toString().contains("strong-tok"), "authToken must not appear in toString()");
    }

    @Test
    @Timeout(30)
    void disabledDefaultCreatesZeroResourcesAndEnabledSharesSingleNodeRegistryAcrossReload(@TempDir Path tempDir) throws Exception {
        // Part 1: Default disabled -> zero NodeRegistry / Rendezvous / Relay created and zero H2 file created
        RssClientConf disabledConf = baseConf(tempDir);
        assertTrue(RssClient.normalizeAndValidateRssConfig(disabledConf));
        RssRuntime disabledRt = new RssRuntime(freePort(), disabledConf);
        try {
            assertNull(disabledRt.nodeRegistryRef);
            assertNull(disabledRt.rendezvousRef);
            assertNull(disabledRt.nodeRelayRef);
            File dbFile = new File(disabledConf.nodeRegistry.getDbPath() + ".mv.db");
            assertFalse(dbFile.exists(), "disabled NodeRegistry must not create H2 database files");
        } finally {
            disabledRt.close();
        }

        // Part 2: Enabled -> shared NodeRegistry across Nameserver + Rendezvous + RrpServer RAW Relay
        RssClientConf enabledConf = baseConf(tempDir);
        String dbPath = tempDir.resolve("node-reg-enabled").toString();
        enabledConf.nodeRegistry.setEnabled(true);
        enabledConf.nodeRegistry.setDbPath(dbPath);
        enabledConf.nodeService.enabled = true;
        enabledConf.nodeService.rendezvousEnabled = true;
        enabledConf.nodeService.relayEnabled = true;
        enabledConf.nodeService.allowEphemeralPorts = true;
        enabledConf.nodeService.rendezvousPort = 0;
        enabledConf.nodeService.udpObservePort = 0;
        enabledConf.nodeService.relayPort = 0;
        enabledConf.nodeService.authToken = "svc-secret-1";
        enabledConf.nodeService.egressConfig = EgressConfig.serverAutoAll();
        assertTrue(RssClient.normalizeAndValidateRssConfig(enabledConf));

        RssRuntime rt = new RssRuntime(freePort(), enabledConf);
        RendezvousContract rvClient = null;
        RrpClient relayClient = null;
        try {
            assertNotNull(rt.nodeRegistryRef);
            assertNotNull(rt.rendezvousRef);
            assertNotNull(rt.nodeRelayRef);
            NodeRegistry initialReg = rt.nodeRegistryRef;
            initialReg.enrollNode("edge-1", "tenant-a", "edge-secret-1", Collections.singleton("peer"), null);

            // Both Rendezvous and RRP RAW Relay authenticate against the EXACT SAME NodeRegistry without evicting each other!
            TransportConfig rvTc = TransportConfig.pureTcpClient(Sockets.newLoopbackEndpoint(rt.rendezvousRef.rpcPort()));
            rvTc.setAuthSecret("svc-secret-1");
            rvTc.setEnableEncryption(true);
            rvTc.setEgressConfig(EgressConfig.client(EgressCodec.HTTP));
            rvTc.getTcpClientConfig().setEnableReconnect(false);
            rvClient = Remoting.createFacade(RendezvousContract.class, new RpcClientConfig<RendezvousContract>(rvTc));
            RendezvousContract.RendezvousAuthResult authRes = rvClient.authenticateNode("edge-1", "edge-secret-1", null, null);
            assertEquals("edge-1", authRes.getNodeId());
            assertEquals("tenant-a", authRes.getTenant());

            RrpConfig rc = new RrpConfig();
            rc.setToken("svc-secret-1");
            rc.setServerEndpoint("127.0.0.1:" + rt.nodeRelayRef.listenPort());
            rc.setEgressConfig(EgressConfig.client(EgressCodec.ZLIB_STORED));
            rc.setEnableReconnect(false);
            rc.setHeartbeatSeconds(0);
            rc.setNodeId("edge-1");
            rc.setNodeSecret("edge-secret-1");
            relayClient = new RrpClient(rc);
            relayClient.connectAsync().get(5, TimeUnit.SECONDS);
            relayClient.awaitRawAuthenticated().get(5, TimeUnit.SECONDS);
            assertTrue(rvClient.heartbeat(), "Rendezvous session must remain active after RRP Relay login");
            assertEquals(1, initialReg.onlineNodeCount(), "node must occupy exactly 1 OnlineNode entry across shared services");

            rvClient.close();
            rvClient = null;
            relayClient.close();
            relayClient = null;

            // Hot-reload with DIFFERENT dbPath -> MUST be rejected without disturbing running NodeRegistry!
            RssClientConf badReload = baseConf(tempDir);
            badReload.shadowDnsPort = enabledConf.shadowDnsPort;
            badReload.nameserver.setRegisterPort(enabledConf.nameserver.getRegisterPort());
            badReload.shadowUsers.get(0).setSsPort(enabledConf.shadowUsers.get(0).getSsPort());
            badReload.nodeRegistry.setEnabled(true);
            badReload.nodeRegistry.setDbPath(tempDir.resolve("different-db-path").toString());
            badReload.nodeService.enabled = true;
            badReload.nodeService.rendezvousEnabled = true;
            badReload.nodeService.relayEnabled = true;
            badReload.nodeService.allowEphemeralPorts = true;
            badReload.nodeService.authToken = "svc-secret-1";
            assertThrows(RuntimeException.class, () -> rt.reload(enabledConf, badReload));
            assertSame(initialReg, rt.nodeRegistryRef, "failed dbPath reload must preserve exact running NodeRegistry");

            // Hot-reload with SAME dbPath + rotated authToken -> reuses exact same NodeRegistry!
            RssClientConf goodReload = baseConf(tempDir);
            goodReload.shadowDnsPort = enabledConf.shadowDnsPort;
            goodReload.nameserver.setRegisterPort(enabledConf.nameserver.getRegisterPort());
            goodReload.shadowUsers.get(0).setSsPort(enabledConf.shadowUsers.get(0).getSsPort());
            goodReload.nodeRegistry.setEnabled(true);
            goodReload.nodeRegistry.setDbPath(dbPath);
            goodReload.nodeService.enabled = true;
            goodReload.nodeService.rendezvousEnabled = true;
            goodReload.nodeService.relayEnabled = false;
            goodReload.nodeService.allowEphemeralPorts = true;
            goodReload.nodeService.authToken = "svc-secret-2";
            rt.reload(enabledConf, goodReload);

            assertSame(initialReg, rt.nodeRegistryRef, "reload must reuse the exact same NodeRegistry instance");
            assertNotNull(rt.rendezvousRef);
            assertNull(rt.nodeRelayRef, "disabling relay on reload must stop nodeRelayRef");

            // Hot-reload failure rollback via reloadPostPublishHook -> keeps previous services intact!
            rt.reloadPostPublishHook = () -> {
                throw new IllegalStateException("simulated reload failure");
            };
            RssClientConf rollbackConf = baseConf(tempDir);
            rollbackConf.shadowDnsPort = enabledConf.shadowDnsPort;
            rollbackConf.nameserver.setRegisterPort(enabledConf.nameserver.getRegisterPort());
            rollbackConf.shadowUsers.get(0).setSsPort(enabledConf.shadowUsers.get(0).getSsPort());
            rollbackConf.nodeRegistry.setEnabled(true);
            rollbackConf.nodeRegistry.setDbPath(dbPath);
            rollbackConf.nodeService.enabled = true;
            rollbackConf.nodeService.rendezvousEnabled = true;
            rollbackConf.nodeService.relayEnabled = true;
            rollbackConf.nodeService.allowEphemeralPorts = true;
            rollbackConf.nodeService.authToken = "svc-secret-3";
            assertThrows(RuntimeException.class, () -> rt.reload(goodReload, rollbackConf));
            rt.reloadPostPublishHook = null;

            assertSame(initialReg, rt.nodeRegistryRef);
            assertNotNull(rt.rendezvousRef);
            assertNull(rt.nodeRelayRef, "rollback must restore previous relayEnabled=false state");
        } finally {
            if (rvClient != null) rvClient.close();
            if (relayClient != null) relayClient.close();
            rt.close();
        }
        assertNull(rt.nodeRegistryRef, "close() must release NodeRegistry reference");
        assertNull(rt.rendezvousRef);
        assertNull(rt.nodeRelayRef);
    }
}
