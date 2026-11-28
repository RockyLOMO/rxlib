package org.rx.net.registry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.rx.io.EntityDatabase;
import org.rx.io.EntityDatabaseImpl;
import org.rx.net.EndpointCandidate;
import org.rx.net.EndpointCandidate.AddressFamily;
import org.rx.net.EndpointCandidate.CandidateType;
import org.rx.net.EndpointCandidate.Protocol;
import org.rx.net.registry.NodeRegistry.LiveService;
import org.rx.net.registry.NodeRegistry.NodeSummary;
import org.rx.net.registry.NodeRegistry.OnlineNode;
import org.rx.net.registry.NodeRegistryEntities.NodeEntity;
import org.rx.net.registry.NodeRegistryEntities.NodeServiceEntity;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class NodeRegistryTest {

    @Test
    @Timeout(30)
    void enrollAuthHotColdRestartIsolationAndZeroPlaintextSecrets(@TempDir Path tempDir) {
        AtomicLong clock = new AtomicLong(1_000_000L);
        String dbPath = tempDir.resolve("reg-restart").toAbsolutePath().toString();
        NodeRegistryConfig cfg = newConfig(dbPath);

        try (NodeRegistry reg = new NodeRegistry(cfg, null, clock::get)) {
            reg.enrollNode("node-1", "tenant-a", "super-secret-pass", Collections.singleton("member"), "{\"region\":\"sg\"}");
            assertTrue(reg.authenticate("node-1", "super-secret-pass"));
            assertFalse(reg.authenticate("node-1", "wrong-pass"));

            // Persist cold attributes and persistent service config
            reg.setAttribute("node-1", "env", "prod");
            reg.setPersistentServiceConfig("node-1", "orders", "ord-1", "{\"weight\":10}");

            // Bring node online with candidates & live service
            EndpointCandidate lan = new EndpointCandidate(AddressFamily.IPV4, "192.168.1.10", 9000,
                    Protocol.UDP, CandidateType.LAN, "nic:eth0", clock.get(), 60_000L, true);
            OnlineNode online = reg.markOnline("node-1", "super-secret-pass", new Object(), Collections.singletonList(lan));
            reg.registerLiveService("node-1", "orders", "ord-1", Collections.singletonMap("ver", "2"));
            assertEquals(1, reg.onlineNodeCount());
            assertEquals(1, reg.discoverLiveServices("node-1", "orders").size());
            assertNotNull(online);

            // Flush coalesced cold writes explicitly
            assertTrue(reg.flushNow() >= 2);
        }

        // Verify raw H2 table does NOT contain plaintext secret!
        try (EntityDatabaseImpl rawDb = new EntityDatabaseImpl(dbPath, null, 2)) {
            rawDb.createMapping(NodeEntity.class);
            NodeEntity stored = rawDb.findById(NodeEntity.class, "node-1");
            assertNotNull(stored);
            assertNotNull(stored.getCredentialVerifier());
            assertTrue(stored.getCredentialVerifier().startsWith("pbkdf2$"));
            assertFalse(stored.getCredentialVerifier().contains("super-secret-pass"), "Plaintext password MUST NOT be stored in H2");
        }

        // Restart NodeRegistry on same H2 file: hot state & live services must be EMPTY, cold data preserved
        try (NodeRegistry restarted = new NodeRegistry(cfg, null, clock::get)) {
            assertEquals(0, restarted.onlineNodeCount(), "Restart must NOT resurrect old online nodes");
            assertNull(restarted.getOnlineNode("node-1"));
            assertTrue(restarted.authenticate("node-1", "super-secret-pass"));
            assertEquals("prod", restarted.getAttributes("node-1").get("env"));
            List<NodeServiceEntity> svcs = restarted.getPersistentServiceConfigs("node-1");
            assertEquals(1, svcs.size());
            assertEquals("{\"weight\":10}", svcs.get(0).getServiceConfigJson());
        }
    }

    @Test
    @Timeout(30)
    void securityCriticalDisableEvictsImmediatelyAndTenantAclIsolatesCandidates(@TempDir Path tempDir) {
        AtomicLong clock = new AtomicLong(2_000_000L);
        NodeRegistryConfig cfg = newConfig(tempDir.resolve("reg-sec").toAbsolutePath().toString());
        AtomicReference<String> revokedId = new AtomicReference<String>();

        try (NodeRegistry reg = new NodeRegistry(cfg, null, clock::get)) {
            reg.setOnNodeRevoked(n -> revokedId.set(n.getNodeId()));

            reg.enrollNode("a-1", "tenant-a", "sec-a1", Collections.singleton("member"), null);
            reg.enrollNode("a-2", "tenant-a", "sec-a2", Collections.singleton("member"), null);
            reg.enrollNode("b-1", "tenant-b", "sec-b1", Collections.singleton("member"), null);
            reg.enrollNode("admin-1", "ops", "sec-adm", Collections.singleton("admin"), null);

            EndpointCandidate priv = new EndpointCandidate(AddressFamily.IPV4, "10.0.0.5", 7000,
                    Protocol.UDP, CandidateType.LAN, "nic:eth0", clock.get(), 60_000L, true);
            reg.markOnline("a-1", "sec-a1", "s1", Collections.singletonList(priv));
            reg.markOnline("a-2", "sec-a2", "s2", Collections.<EndpointCandidate>emptyList());
            reg.markOnline("b-1", "sec-b1", "s3", Collections.<EndpointCandidate>emptyList());
            reg.markOnline("admin-1", "sec-adm", "s4", Collections.<EndpointCandidate>emptyList());

            // Same tenant and admin can discover private candidates; cross-tenant cannot
            assertEquals(1, reg.discoverAuthorizedCandidates("a-2", "a-1").size());
            assertEquals(1, reg.discoverAuthorizedCandidates("admin-1", "a-1").size());
            assertTrue(reg.discoverAuthorizedCandidates("b-1", "a-1").isEmpty(), "Cross-tenant peer must not see private candidates");

            // Disabling a-1 persists immediately, invalidates cache, evicts online session, fires revoke callback
            reg.setDisabled("a-1", true);
            assertEquals("a-1", revokedId.get());
            assertNull(reg.getOnlineNode("a-1"));
            assertFalse(reg.authenticate("a-1", "sec-a1"));
            assertThrows(SecurityException.class, () -> reg.markOnline("a-1", "sec-a1", "s1b", null));

            // Credential rotation immediately invalidates old secret
            reg.updateCredential("a-2", "new-sec-a2");
            assertFalse(reg.authenticate("a-2", "sec-a2"));
            assertTrue(reg.authenticate("a-2", "new-sec-a2"));
        }
    }

    @Test
    @Timeout(90)
    void scale1024OnlineNodesEnforcesAllBudgetsPaginationTtlAndPrune(@TempDir Path tempDir) {
        AtomicLong clock = new AtomicLong(5_000_000L);
        NodeRegistryConfig cfg = newConfig(tempDir.resolve("reg-scale").toAbsolutePath().toString());
        cfg.setMaxOnlineNodes(1024);
        cfg.setMaxCandidatesPerNode(16);
        cfg.setMaxAttrsBytesPerNode(8192);
        cfg.setMaxColdCacheBytes(64 * 1024); // tight cold cache weight to exercise LRU eviction
        cfg.setMaxQueryPageSize(50);
        cfg.setMaxAuthAttemptsPerSecond(0); // test 1024 logins at single frozen timestamp
        cfg.setPbkdf2Iterations(1_000); // keep real salted PBKDF2-HMAC-SHA256 while fitting JDK 8 software SHA-256 within timeout

        try (NodeRegistry reg = new NodeRegistry(cfg, null, clock::get)) {
            // Enroll 1030 nodes (1024 online + 6 offline)
            String secret = "node-pass-1";
            // Fast batch enroll via public API for 120 real nodes + verify 1024 online cap cleanly:
            // First verify small cap & 1024 cap by enrolling 1025 nodes with a fast shared PBKDF2?
            // Note: 1025 PBKDF2(10k) takes ~2-3s total on modern JVM; let's verify all 1025!
            for (int i = 0; i < 1025; i++) {
                String id = String.format("n-%04d", i);
                reg.enrollNode(id, i % 2 == 0 ? "t0" : "t1", secret, Collections.singleton("member"), "{\"i\":" + i + "}");
            }

            // Test 16-candidate cap on node 0
            List<EndpointCandidate> manyCands = new ArrayList<EndpointCandidate>();
            for (int c = 0; c < 30; c++) {
                manyCands.add(new EndpointCandidate(AddressFamily.IPV4, "10.1.0." + (c + 1), 8000 + c,
                        Protocol.UDP, CandidateType.LAN, "eth0", clock.get(), 30_000L, false));
            }
            for (int i = 0; i < 1024; i++) {
                String id = String.format("n-%04d", i);
                reg.markOnline(id, secret, i, i == 0 ? manyCands : Collections.<EndpointCandidate>emptyList());
            }
            assertEquals(1024, reg.onlineNodeCount());
            assertEquals(16, reg.getOnlineNode("n-0000").getCandidates().size(), "Candidates per node must cap at 16");
            assertTrue(reg.coldCacheBytesUsed() <= 64 * 1024, "Cold cache weight must stay within configured byte budget");

            // 1025th online node is rejected when cap=1024 is full
            assertThrows(IllegalStateException.class, () -> reg.markOnline("n-1024", secret, 1024, null));

            // 8 KiB attribute budget per node enforced (single value <= 2048B)
            char[] chunkChars = new char[1800];
            Arrays.fill(chunkChars, 'a');
            String chunkVal = new String(chunkChars);
            reg.setAttribute("n-0000", "k1", chunkVal);
            reg.setAttribute("n-0000", "k2", chunkVal);
            reg.setAttribute("n-0000", "k3", chunkVal);
            reg.setAttribute("n-0000", "k4", chunkVal); // 4 * 1802 = 7208 <= 8192
            assertThrows(IllegalArgumentException.class, () -> reg.setAttribute("n-0000", "k5", chunkVal));
            assertTrue(reg.flushNow() > 0);

            // Paginated query clamps limit to maxQueryPageSize=50
            List<NodeSummary> page1 = reg.queryNodes("t0", 0, 500);
            assertEquals(50, page1.size(), "queryNodes must clamp limit to maxQueryPageSize");
            List<NodeSummary> page2 = reg.queryNodes("t0", 50, 50);
            assertEquals(50, page2.size());
            assertNotEquals(page1.get(0).getNodeId(), page2.get(0).getNodeId());

            // Heartbeat keeps n-0000 alive while other 1023 expire on shared TTL sweep
            clock.addAndGet(50_000L);
            assertTrue(reg.heartbeat("n-0000", null));
            clock.addAndGet(50_000L); // total 100s > 90s TTL for others, 50s < 90s for n-0000
            reg.sweepExpiredOnlineNodes();
            assertEquals(1, reg.onlineNodeCount());
            assertNotNull(reg.getOnlineNode("n-0000"));
            assertTrue(reg.getOnlineNode("n-0000").getCandidates().isEmpty(), "Expired candidates swept");

            // Flush lastSeen and prune stale offline nodes
            reg.flushNow();
            int pruned = reg.pruneOfflineNodesOlderThan(clock.get() - 10_000L, 100);
            assertEquals(100, pruned);
        }
    }

    @Test
    @Timeout(20)
    void attributeBudgetAndPendingQueueBoundsAndDbFailureFailClosed(@TempDir Path tempDir) {
        AtomicLong clock = new AtomicLong(9_000_000L);
        String dbPath = tempDir.resolve("reg-fail").toAbsolutePath().toString();
        EntityDatabaseImpl realDb = new EntityDatabaseImpl(dbPath, null, 2);
        AtomicBoolean failDb = new AtomicBoolean();
        EntityDatabase faultDb = (EntityDatabase) java.lang.reflect.Proxy.newProxyInstance(
                EntityDatabase.class.getClassLoader(),
                new Class<?>[]{EntityDatabase.class},
                (proxy, method, args) -> {
                    if (failDb.get() && !"close".equals(method.getName()) && !"tableName".equals(method.getName())) {
                        throw new RuntimeException("Simulated H2 disk full / I/O failure");
                    }
                    try {
                        return method.invoke(realDb, args);
                    } catch (java.lang.reflect.InvocationTargetException ite) {
                        throw ite.getCause();
                    }
                });

        NodeRegistryConfig cfg = newConfig(dbPath);
        cfg.setMaxAttrsBytesPerNode(1024);
        cfg.setMaxPendingPersistBytes(4096);

        try (NodeRegistry reg = new NodeRegistry(cfg, faultDb, clock::get)) {
            reg.enrollNode("n1", "t1", "p1", Collections.singleton("member"), null);
            reg.enrollNode("n2", "t1", "p2", Collections.singleton("member"), null);

            // Attribute 1024-byte budget
            char[] okVal = new char[500];
            Arrays.fill(okVal, 'x');
            reg.setAttribute("n1", "k1", new String(okVal));
            char[] tooBig = new char[600];
            Arrays.fill(tooBig, 'y');
            assertThrows(IllegalArgumentException.class, () -> reg.setAttribute("n1", "k2", new String(tooBig)));
            assertTrue(reg.pendingPersistBytes() <= 4096L);

            // Simulate H2 failure: cold auth for uncached n2 MUST fail closed, and flush failure retains writes & backs off
            failDb.set(true);
            assertFalse(reg.authenticate("n2", "p2"), "H2 failure must fail closed on auth");
            assertThrows(SecurityException.class, () -> reg.markOnline("n2", "p2", "s2", null));
            assertThrows(RuntimeException.class, reg::flushNow);
            assertEquals(1L, reg.flushFailureCount());
            assertTrue(reg.pendingPersistBytes() > 0L, "Failed flush must retain pending writes for retry");

            // Recover H2: retry flush succeeds and auth works again
            failDb.set(false);
            assertTrue(reg.flushNow() >= 1);
            assertEquals(0L, reg.pendingPersistBytes());
            assertTrue(reg.authenticate("n2", "p2"));
            assertEquals(new String(okVal), reg.getAttributes("n1").get("k1"));
        } finally {
            realDb.close();
        }
    }

    @Test
    @Timeout(20)
    void concurrentHeartbeatsAndQueriesAreThreadSafe(@TempDir Path tempDir) throws Exception {
        NodeRegistryConfig cfg = newConfig(tempDir.resolve("reg-conc").toAbsolutePath().toString());
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try (NodeRegistry reg = new NodeRegistry(cfg)) {
            for (int i = 0; i < 20; i++) {
                reg.enrollNode("c-" + i, "tenant-c", "pw", Collections.singleton("member"), null);
                reg.markOnline("c-" + i, "pw", i, Collections.<EndpointCandidate>emptyList());
            }
            CountDownLatch latch = new CountDownLatch(4);
            AtomicInteger errors = new AtomicInteger();
            for (int t = 0; t < 4; t++) {
                final int tid = t;
                pool.submit(() -> {
                    try {
                        for (int r = 0; r < 50; r++) {
                            String id = "c-" + ((tid * 5 + r) % 20);
                            reg.heartbeat(id, null);
                            reg.discoverAuthorizedCandidates(id, "c-0");
                            if (r % 10 == 0) {
                                reg.queryNodes("tenant-c", 0, 10);
                            }
                        }
                    } catch (Throwable ex) {
                        errors.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }
            assertTrue(latch.await(15, TimeUnit.SECONDS));
            assertEquals(0, errors.get());
            reg.flushNow();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @Timeout(20)
    void openIfEnabledDisabledReturnsNullAndCreatesNoDb(@TempDir Path tempDir) {
        NodeRegistryConfig disabled = new NodeRegistryConfig();
        disabled.setEnabled(false);
        disabled.setDbPath(tempDir.resolve("should-not-exist").toAbsolutePath().toString());
        assertNull(NodeRegistry.openIfEnabled(disabled));
        assertNull(NodeRegistry.openIfEnabled(null));
        assertFalse(tempDir.resolve("should-not-exist.mv.db").toFile().exists());
    }

    @Test
    @Timeout(20)
    void atomicOnlineCapReconnectReplacementStaleSessionAndServiceBudgets(@TempDir Path tempDir) {
        AtomicLong clock = new AtomicLong(10_000_000L);
        NodeRegistryConfig cfg = newConfig(tempDir.resolve("reg-hardening").toAbsolutePath().toString());
        cfg.setMaxOnlineNodes(2);
        cfg.setMaxLiveServicesPerNode(2);
        cfg.setMaxPersistentServicesPerNode(2);
        cfg.setMaxServiceMetadataBytesPerNode(64);
        cfg.setMaxQueryPageSize(2);
        cfg.setMaxAuthAttemptsPerSecond(0);

        List<Object> revokedSessions = Collections.synchronizedList(new ArrayList<Object>());
        try (NodeRegistry reg = new NodeRegistry(cfg, null, clock::get)) {
            reg.addOnNodeRevokedListener(n -> revokedSessions.add(n.getSessionRef()));
            reg.enrollNode("n1", "t1", "p1", Collections.singleton("member"), null);
            reg.enrollNode("n2", "t1", "p2", Collections.singleton("member"), null);
            reg.enrollNode("n3", "t1", "p3", Collections.singleton("member"), null);

            Object sess1Old = new Object();
            Object sess1New = new Object();
            Object sess2 = new Object();
            reg.markOnline("n1", "p1", sess1Old, null);
            reg.markOnline("n2", "p2", sess2, null);
            // Cap=2 reached for distinct node n3
            assertThrows(IllegalStateException.class, () -> reg.markOnline("n3", "p3", new Object(), null));

            // Reconnect replacement for n1 succeeds without consuming extra slot and revokes old session
            reg.markOnline("n1", "p1", sess1New, null);
            assertEquals(2, reg.onlineNodeCount());
            assertTrue(revokedSessions.contains(sess1Old), "Replacing session must revoke old session");

            // Stale session CANNOT heartbeat, registerLiveService, deregisterLiveService, or markOffline
            assertFalse(reg.heartbeat("n1", sess1Old, null));
            assertTrue(reg.heartbeat("n1", sess1New, null));
            assertThrows(SecurityException.class, () -> reg.registerLiveService("n1", sess1Old, "svc", "i1", null));
            assertFalse(reg.markOffline("n1", sess1Old));
            assertNotNull(reg.getOnlineNode("n1"));

            // Live service per-node cap (2) and metadata byte cap (64B) enforced
            reg.registerLiveService("n1", sess1New, "svc", "i1", Collections.singletonMap("k", "1234567890"));
            reg.registerLiveService("n1", sess1New, "svc", "i2", Collections.singletonMap("k", "1234567890"));
            assertThrows(IllegalStateException.class, () -> reg.registerLiveService("n1", sess1New, "svc", "i3", null));
            // Oversized metadata on existing instance rejected
            char[] bigMeta = new char[80];
            Arrays.fill(bigMeta, 'm');
            assertThrows(IllegalArgumentException.class,
                    () -> reg.registerLiveService("n1", sess1New, "svc", "i1", Collections.singletonMap("k", new String(bigMeta))));

            // Stale session cannot deregister new session's live service
            reg.deregisterLiveService("n1", sess1Old, "svc", "i1");
            assertEquals(2, reg.discoverLiveServices("n2", "svc", 0, 10).size());

            // Paginated live service discovery clamps limit to maxQueryPageSize=2
            reg.registerLiveService("n2", sess2, "svc", "i3", null);
            List<LiveService> p1 = reg.discoverLiveServices("n2", "svc", 0, 10);
            List<LiveService> p2 = reg.discoverLiveServices("n2", "svc", 2, 10);
            assertEquals(2, p1.size());
            assertEquals(1, p2.size());

            // Persistent service per-node cap (2) enforced
            reg.setPersistentServiceConfig("n1", "svc", "p1", "{\"a\":1}");
            reg.setPersistentServiceConfig("n1", "svc", "p2", "{\"a\":2}");
            assertThrows(IllegalArgumentException.class, () -> reg.setPersistentServiceConfig("n1", "svc", "p3", "{\"a\":3}"));
        }
    }

    @Test
    @Timeout(20)
    void authRateLimitAndConcurrentFlushAttributeLinearizability(@TempDir Path tempDir) throws Exception {
        AtomicLong clock = new AtomicLong(20_000_000L);
        String dbPath = tempDir.resolve("reg-rate").toAbsolutePath().toString();
        EntityDatabaseImpl realDb = new EntityDatabaseImpl(dbPath, null, 2);
        CountDownLatch enteredFlush = new CountDownLatch(1);
        CountDownLatch releaseFlush = new CountDownLatch(1);
        AtomicBoolean pauseNextCommit = new AtomicBoolean();
        EntityDatabase slowDb = (EntityDatabase) java.lang.reflect.Proxy.newProxyInstance(
                EntityDatabase.class.getClassLoader(),
                new Class<?>[]{EntityDatabase.class},
                (proxy, method, args) -> {
                    if ("withConnection".equals(method.getName()) && pauseNextCommit.compareAndSet(true, false)) {
                        enteredFlush.countDown();
                        releaseFlush.await(5, TimeUnit.SECONDS);
                    }
                    try {
                        return method.invoke(realDb, args);
                    } catch (java.lang.reflect.InvocationTargetException ite) {
                        throw ite.getCause();
                    }
                });

        NodeRegistryConfig cfg = newConfig(dbPath);
        cfg.setMaxAuthAttemptsPerSecond(3);
        cfg.setMaxAttrsBytesPerNode(1024);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (NodeRegistry reg = new NodeRegistry(cfg, slowDb, clock::get)) {
            reg.enrollNode("rl-1", "t1", "secret", Collections.singleton("member"), null);
            // Token bucket allows 3 attempts at same timestamp, 4th rejected fail-closed
            assertTrue(reg.authenticate("rl-1", "secret"));
            assertTrue(reg.authenticate("rl-1", "secret"));
            assertTrue(reg.authenticate("rl-1", "secret"));
            assertFalse(reg.authenticate("rl-1", "secret"), "4th auth attempt within 0ms must be rate-limited");
            assertThrows(SecurityException.class, () -> reg.markOnline("rl-1", "secret", "s", null));
            assertTrue(reg.authRejectedCount() >= 2L);
            // Advance clock 1 second -> tokens refill
            clock.addAndGet(1000L);
            assertTrue(reg.authenticate("rl-1", "secret"));

            // In-flight flush keeps pendingPersistBytes > 0 AND keeps attributes visible to concurrent setAttribute budget check
            char[] v500 = new char[500];
            Arrays.fill(v500, 'a');
            reg.setAttribute("rl-1", "k1", new String(v500));
            pauseNextCommit.set(true);
            java.util.concurrent.Future<Integer> flushFut = pool.submit(reg::flushNow);
            assertTrue(enteredFlush.await(5, TimeUnit.SECONDS));
            assertTrue(reg.pendingPersistBytes() > 0L, "in-flight flush bytes must remain accounted");
            // While k1 (502B) is in-flight, adding k2 (600B) MUST see k1 and reject (> 1024B budget)
            char[] v600 = new char[600];
            Arrays.fill(v600, 'b');
            assertThrows(IllegalArgumentException.class, () -> reg.setAttribute("rl-1", "k2", new String(v600)));
            releaseFlush.countDown();
            assertEquals(1, flushFut.get(5, TimeUnit.SECONDS).intValue());
            assertEquals(0L, reg.pendingPersistBytes());
            assertEquals(new String(v500), reg.getAttributes("rl-1").get("k1"));
        } finally {
            pool.shutdownNow();
            realDb.close();
        }
    }

    private static NodeRegistryConfig newConfig(String dbPath) {
        NodeRegistryConfig cfg = new NodeRegistryConfig();
        cfg.setEnabled(true);
        cfg.setDbPath(dbPath);
        cfg.setFlushPeriodMillis(0L);
        cfg.setTtlSweepPeriodMillis(0L);
        cfg.setOnlineTtlMillis(90_000L);
        return cfg;
    }
}
