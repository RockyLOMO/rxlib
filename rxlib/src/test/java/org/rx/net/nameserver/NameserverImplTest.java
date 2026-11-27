package org.rx.net.nameserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.rx.bean.RandomList;
import org.rx.core.RxConfig;
import org.rx.net.Sockets;
import org.rx.net.dns.DnsServer;
import org.rx.net.http.HttpServer;
import org.rx.net.nameserver.NameserverReplicaProtocol.ReplicaAck;
import org.rx.net.nameserver.NameserverReplicaProtocol.ReplicaDeltaRequest;
import org.rx.net.nameserver.NameserverReplicaProtocol.ReplicaRecord;
import org.rx.net.nameserver.NameserverReplicaProtocol.ReplicaSnapshotPage;
import org.rx.net.rpc.Remoting;
import org.rx.net.rpc.RemotingException;
import org.rx.net.rpc.RpcClientConfig;

import java.io.Serializable;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.rx.core.Extends.sleep;

class NameserverImplTest {
    private static final String CLUSTER_TOKEN = "test-ns-cluster-secret-2026";

    @Test
    @Timeout(20)
    void registerAndDeregisterUsesTransportSessionAttr() throws Exception {
        int dnsPort = freePort();
        int registerPort = freePort();
        NameserverImpl server = newServer("ns-single", dnsPort, registerPort, null, CLUSTER_TOKEN);
        Nameserver client = newClient(registerPort);
        try {
            Set<InetSocketAddress> endpoints = Collections.singleton(new InetSocketAddress("127.0.0.1", registerPort));

            client.register("order", endpoints);
            assertContainsLoopback(server.discover("order"));

            client.deregister();

            awaitTrue(() -> server.discover("order").isEmpty(), 3000, "nameserver should clear order");
        } finally {
            client.close();
            server.close();
        }
    }

    @Test
    @Timeout(20)
    void clientDisconnectDeregistersBeforeTransportAttrsAreCleared() throws Exception {
        int dnsPort = freePort();
        int registerPort = freePort();
        NameserverImpl server = newServer("ns-disc", dnsPort, registerPort, null, CLUSTER_TOKEN);
        Nameserver client = newClient(registerPort);
        try {
            Set<InetSocketAddress> endpoints = Collections.singleton(new InetSocketAddress("127.0.0.1", registerPort));

            client.register("billing", endpoints);
            assertContainsLoopback(server.discover("billing"));

            client.close();

            awaitTrue(() -> server.discover("billing").isEmpty(), 5000, "nameserver should clear billing on disconnect");
        } finally {
            server.close();
        }
    }

    @Test
    @Timeout(20)
    void nameserverClientRegistersPublicIpAttr() throws Exception {
        int dnsPort = freePort();
        int registerPort = freePort();
        NameserverImpl server = newServer("ns-pubip", dnsPort, registerPort, null, CLUSTER_TOKEN);
        NameserverClient client = new NameserverClient("inventory");
        client.publicIpResolver = () -> "1.1.1.1";
        try {
            client.registerAsync(Collections.singleton(new InetSocketAddress("127.0.0.1", registerPort))).get(15, TimeUnit.SECONDS);

            List<Nameserver.InstanceInfo> infos = server.discover("inventory", Collections.singletonList(Nameserver.PUBLIC_IP_KEY));

            assertEquals(1, infos.size());
            assertEquals("1.1.1.1", infos.get(0).getAttributes().get(Nameserver.PUBLIC_IP_KEY));
            assertEquals(client.getInstanceId(), infos.get(0).getInstanceId());
        } finally {
            client.close();
            server.close();
        }
    }

    @Test
    @Timeout(20)
    void multipleSameIpInstancesHaveIsolatedAttrsAndDeregister() throws Exception {
        int dnsPort = freePort();
        int registerPort = freePort();
        NameserverImpl server = newServer("ns-sameip", dnsPort, registerPort, null, CLUSTER_TOKEN);
        Nameserver c1 = newClient(registerPort);
        Nameserver c2 = newClient(registerPort);
        try {
            Set<InetSocketAddress> eps = Collections.singleton(new InetSocketAddress("127.0.0.1", registerPort));
            c1.register("gateway", RandomList.DEFAULT_WEIGHT, "inst-a", eps);
            c2.register("gateway", RandomList.DEFAULT_WEIGHT, "inst-b", eps);
            c1.instanceAttr("gateway", "role", "primary");
            c2.instanceAttr("gateway", "role", "canary");

            List<Nameserver.InstanceInfo> infos = server.discoverAll("gateway", false, Collections.singletonList("role"));
            assertEquals(2, infos.size());
            Map<String, Object> roleByInst = new HashMap<String, Object>();
            for (Nameserver.InstanceInfo info : infos) {
                roleByInst.put(info.getInstanceId(), info.getAttributes().get("role"));
            }
            assertEquals("primary", roleByInst.get("inst-a"));
            assertEquals("canary", roleByInst.get("inst-b"));

            // Deregistering inst-a must preserve inst-b and keep loopback IP in DNS
            c1.deregister();
            awaitTrue(() -> server.discoverAll("gateway", false, Collections.singletonList("role")).size() == 1,
                    3000, "only inst-b should remain");
            List<Nameserver.InstanceInfo> remaining = server.discoverAll("gateway", false, Collections.singletonList("role"));
            assertEquals("inst-b", remaining.get(0).getInstanceId());
            assertEquals("canary", remaining.get(0).getAttributes().get("role"));
            assertContainsLoopback(server.discover("gateway"));

            // Deregistering inst-b removes DNS entry
            c2.deregister();
            awaitTrue(() -> server.discover("gateway").isEmpty(), 3000, "gateway DNS should be empty after last instance leaves");
        } finally {
            c1.close();
            c2.close();
            server.close();
        }
    }

    @Test
    @Timeout(25)
    void twoNodeRpcReplicationConvergesRegisterAttrsDeregisterAndPreservesStaticLocalDns() throws Exception {
        int dns1 = freePort(), reg1 = freePort();
        int dns2 = freePort(), reg2 = freePort();
        String ep1 = "127.0.0.1:" + reg1;
        String ep2 = "127.0.0.1:" + reg2;

        NameserverImpl node1 = newServer("ns-1", dns1, reg1, Collections.singletonList(ep2), CLUSTER_TOKEN);
        NameserverImpl node2 = newServer("ns-2", dns2, reg2, Collections.singletonList(ep1), CLUSTER_TOKEN);
        Nameserver client1 = newClient(reg1);
        Nameserver client2 = newClient(reg2);
        try {
            // Static local DNS record on node2 must NEVER be wiped by remote full sync from node1
            InetAddress staticIp = InetAddress.getByName("10.88.0.9");
            node2.getDnsServer().addHosts("static.internal", "10.88.0.9");

            client1.register("orders", RandomList.DEFAULT_WEIGHT, "ord-1", Collections.singleton(new InetSocketAddress("127.0.0.1", reg1)));
            client1.instanceAttr("orders", "zone", "az1");
            client1.attr("orders", "mode", "active");

            client2.register("payments", RandomList.DEFAULT_WEIGHT, "pay-1", Collections.singleton(new InetSocketAddress("127.0.0.1", reg2)));

            awaitTrue(() -> !node2.discover("orders").isEmpty() && "active".equals(node2.attr("orders", "mode")),
                    8000, "node2 should replicate orders and app attr from node1");
            awaitTrue(() -> {
                List<Nameserver.InstanceInfo> infos = node2.discover("orders", Collections.singletonList("zone"));
                return infos.size() == 1 && "ord-1".equals(infos.get(0).getInstanceId()) && "az1".equals(infos.get(0).getAttributes().get("zone"));
            }, 8000, "node2 should replicate instance attr");
            awaitTrue(() -> !node1.discover("payments").isEmpty(), 8000, "node1 should replicate payments from node2");

            // Trigger full snapshot from node1 -> node2; node2's local payments AND static DNS must stay intact
            node1.syncFullSnapshot();
            sleep(300);
            assertEquals(staticIp, node2.discover("static.internal").get(0));
            assertContainsLoopback(node2.discover("payments"));
            assertContainsLoopback(node2.discover("orders"));

            // Deregister on node1 propagates to node2
            client1.deregister();
            awaitTrue(() -> node2.discover("orders").isEmpty(), 8000, "node2 should remove orders after node1 deregister");
            // Static DNS still untouched
            assertEquals(staticIp, node2.discover("static.internal").get(0));
        } finally {
            client1.close();
            client2.close();
            node1.close();
            node2.close();
        }
    }

    @Test
    @Timeout(30)
    void threeNodeRpcReplicationAndConcurrentMutationsConverge() throws Exception {
        int dns1 = freePort(), reg1 = freePort();
        int dns2 = freePort(), reg2 = freePort();
        int dns3 = freePort(), reg3 = freePort();
        String ep1 = "127.0.0.1:" + reg1;
        String ep2 = "127.0.0.1:" + reg2;
        String ep3 = "127.0.0.1:" + reg3;

        NameserverImpl n1 = newServer("ns-1", dns1, reg1, Arrays.asList(ep2, ep3), CLUSTER_TOKEN);
        NameserverImpl n2 = newServer("ns-2", dns2, reg2, Arrays.asList(ep1, ep3), CLUSTER_TOKEN);
        NameserverImpl n3 = newServer("ns-3", dns3, reg3, Arrays.asList(ep1, ep2), CLUSTER_TOKEN);
        List<Nameserver> clients = new ArrayList<Nameserver>();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            for (int i = 0; i < 6; i++) {
                int port = i % 3 == 0 ? reg1 : (i % 3 == 1 ? reg2 : reg3);
                clients.add(newClient(port));
            }
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(6);
            for (int i = 0; i < 6; i++) {
                final int idx = i;
                pool.submit(() -> {
                    try {
                        start.await();
                        Nameserver c = clients.get(idx);
                        c.register("mesh-app", RandomList.DEFAULT_WEIGHT, "m-" + idx, Collections.<InetSocketAddress>emptySet());
                        c.instanceAttr("mesh-app", "idx", Integer.valueOf(idx));
                        if (idx % 2 == 0) {
                            // Half immediately deregister concurrently
                            c.deregister();
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(15, TimeUnit.SECONDS));

            // Surviving instances are m-1, m-3, m-5 across all 3 nodes
            awaitTrue(() -> instanceIds(n1, "mesh-app").equals(new HashSet<String>(Arrays.asList("m-1", "m-3", "m-5")))
                    && instanceIds(n2, "mesh-app").equals(new HashSet<String>(Arrays.asList("m-1", "m-3", "m-5")))
                    && instanceIds(n3, "mesh-app").equals(new HashSet<String>(Arrays.asList("m-1", "m-3", "m-5"))),
                    10_000, "all 3 nodes should converge to surviving instances m-1, m-3, m-5");
        } finally {
            pool.shutdownNow();
            for (Nameserver c : clients) {
                c.close();
            }
            n1.close();
            n2.close();
            n3.close();
        }
    }

    @Test
    @Timeout(30)
    void disconnectRestartEpochRecoveryAndNoGhostInstances() throws Exception {
        int dns1 = freePort(), reg1 = freePort();
        int dns2 = freePort(), reg2 = freePort();
        String ep1 = "127.0.0.1:" + reg1;
        String ep2 = "127.0.0.1:" + reg2;

        NameserverImpl n1 = newServer("ns-a", dns1, reg1, Collections.singletonList(ep2), CLUSTER_TOKEN);
        NameserverImpl n2 = newServer("ns-b", dns2, reg2, Collections.singletonList(ep1), CLUSTER_TOKEN);
        Nameserver c1 = newClient(reg1);
        Nameserver c2Old = newClient(reg2);
        Nameserver c2New = null;
        try {
            c1.register("svc-a", RandomList.DEFAULT_WEIGHT, "a-1", Collections.<InetSocketAddress>emptySet());
            c2Old.register("svc-b", RandomList.DEFAULT_WEIGHT, "b-old", Collections.<InetSocketAddress>emptySet());

            final NameserverImpl initialN2 = n2;
            awaitTrue(() -> instanceIds(n1, "svc-b").contains("b-old") && instanceIds(initialN2, "svc-a").contains("a-1"),
                    8000, "initial convergence");

            // Simulate node2 crash/shutdown
            c2Old.close();
            n2.close();
            n2 = null;

            // Register another instance on n1 while n2 is down
            Nameserver c1Extra = newClient(reg1);
            try {
                c1Extra.register("svc-a", RandomList.DEFAULT_WEIGHT, "a-2", Collections.<InetSocketAddress>emptySet());
                sleep(200);

                // Restart node2 with the SAME nodeId & registerPort -> strictly higher epoch!
                n2 = newServer("ns-b", dns2, reg2, Collections.singletonList(ep1), CLUSTER_TOKEN);
                c2New = newClient(reg2);
                c2New.register("svc-b", RandomList.DEFAULT_WEIGHT, "b-new", Collections.<InetSocketAddress>emptySet());

                final NameserverImpl restartedN2 = n2;
                // n1 must evict ghost "b-old" from prior epoch and show only "b-new"
                awaitTrue(() -> instanceIds(n1, "svc-b").equals(Collections.singleton("b-new")),
                        10_000, "n1 must drop old-epoch ghost b-old and show b-new");
                // Restarted n2 must recover both a-1 and a-2 from n1 via paged full snapshot
                awaitTrue(() -> instanceIds(restartedN2, "svc-a").equals(new HashSet<String>(Arrays.asList("a-1", "a-2"))),
                        10_000, "restarted n2 must recover a-1 and a-2 from n1");
            } finally {
                c1Extra.close();
            }
        } finally {
            c1.close();
            if (c2New != null) {
                c2New.close();
            }
            if (n2 != null) {
                n2.close();
            }
            n1.close();
        }
    }

    @Test
    @Timeout(25)
    void largePagedSnapshotExceedingMtuAndPageSizeReplicatesAtomically() throws Exception {
        int dns1 = freePort(), reg1 = freePort();
        int dns2 = freePort(), reg2 = freePort();
        String ep1 = "127.0.0.1:" + reg1;
        String ep2 = "127.0.0.1:" + reg2;

        NameserverConfig cfg1 = baseConfig("ns-big-1", dns1, reg1, Collections.singletonList(ep2), CLUSTER_TOKEN);
        cfg1.setReplicaMaxSnapshotPageSize(12);
        NameserverConfig cfg2 = baseConfig("ns-big-2", dns2, reg2, Collections.singletonList(ep1), CLUSTER_TOKEN);
        cfg2.setReplicaMaxSnapshotPageSize(12);

        NameserverImpl n1 = new NameserverImpl(cfg1);
        NameserverImpl n2 = new NameserverImpl(cfg2);
        try {
            // Populate 40 instances with padded payload attrs (> 10 KiB total, 4 pages of 12)
            StringBuilder pad = new StringBuilder();
            for (int i = 0; i < 200; i++) {
                pad.append('x');
            }
            String payload = pad.toString();
            InetAddress ip = InetAddress.getByName("127.0.0.1");
            synchronized (n1.stateLock) {
                for (int i = 0; i < 40; i++) {
                    Map<String, Serializable> attrs = new HashMap<String, Serializable>();
                    attrs.put("blob", payload + "-" + i);
                    ReplicaRecord rec = new ReplicaRecord("big-app", "inst-" + i, ip, RandomList.DEFAULT_WEIGHT, attrs);
                    n1.localPartition.instances.put(NameserverImpl.instanceKey("big-app", "inst-" + i), rec);
                }
                n1.localPartition.lastAppliedVersion = 40L;
            }

            List<ReplicaSnapshotPage> pages = n1.buildLocalSnapshotPages(CLUSTER_TOKEN);
            assertEquals(4, pages.size());

            n1.syncFullSnapshot();
            awaitTrue(() -> n2.discoverAll("big-app", false, Collections.singletonList("blob")).size() == 40,
                    10_000, "n2 must atomically commit all 40 instances from 4 pages");
        } finally {
            n1.close();
            n2.close();
        }
    }

    @Test
    @Timeout(20)
    void staleVersionDuplicateReplayAndTombstonePreventGhostResurrection() throws Exception {
        NameserverImpl server = newServer("ns-ver", freePort(), freePort(), null, CLUSTER_TOKEN);
        try {
            InetAddress ip = InetAddress.getByName("127.0.0.2");
            long epoch = 100L;

            ReplicaDeltaRequest upsert1 = ReplicaDeltaRequest.upsert("peer-a", epoch, 1L, 0L, 11L,
                    new ReplicaRecord("svc", "i1", ip, 1, Collections.<String, Serializable>emptyMap()));
            upsert1.setAuthTag(NameserverReplicaProtocol.signDelta(CLUSTER_TOKEN, upsert1));
            ReplicaAck ack1 = server.replicaApplyDelta(upsert1);
            assertTrue(ack1.isAccepted());
            assertFalse(ack1.isNeedFullSync());
            assertEquals(1, server.discover("svc").size());

            // Duplicate version 1 is idempotent
            ReplicaAck ackDup = server.replicaApplyDelta(upsert1);
            assertTrue(ackDup.isAccepted());
            assertEquals(1L, ackDup.getAppliedVersion());

            // Remove at version 2 creates tombstone
            ReplicaDeltaRequest remove2 = ReplicaDeltaRequest.remove("peer-a", epoch, 2L, 1L, 12L, "svc", "i1");
            remove2.setAuthTag(NameserverReplicaProtocol.signDelta(CLUSTER_TOKEN, remove2));
            ReplicaAck ack2 = server.replicaApplyDelta(remove2);
            assertTrue(ack2.isAccepted());
            assertTrue(server.discover("svc").isEmpty());

            // Stale snapshot at version 1 must be rejected (no ghost resurrection)
            ReplicaSnapshotPage stalePage = new ReplicaSnapshotPage();
            stalePage.setSourceId("peer-a");
            stalePage.setSourceEpoch(epoch);
            stalePage.setVersion(1L);
            stalePage.setSnapshotId(1L);
            stalePage.setPageIndex(0);
            stalePage.setTotalPages(1);
            stalePage.setNonce(13L);
            stalePage.setRecords(Collections.singletonList(
                    new ReplicaRecord("svc", "i1", ip, 1, Collections.<String, Serializable>emptyMap())));
            stalePage.setAuthTag(NameserverReplicaProtocol.signSnapshotPage(CLUSTER_TOKEN, stalePage));
            ReplicaAck staleAck = server.replicaApplySnapshotPage(stalePage);
            assertFalse(staleAck.isAccepted());
            assertTrue(server.discover("svc").isEmpty());

            // Gap delta (version 5 with baseVersion 4 while applied=2) requests full sync
            ReplicaDeltaRequest gap = ReplicaDeltaRequest.upsert("peer-a", epoch, 5L, 4L, 14L,
                    new ReplicaRecord("svc", "i2", ip, 1, Collections.<String, Serializable>emptyMap()));
            gap.setAuthTag(NameserverReplicaProtocol.signDelta(CLUSTER_TOKEN, gap));
            ReplicaAck gapAck = server.replicaApplyDelta(gap);
            assertFalse(gapAck.isAccepted());
            assertTrue(gapAck.isNeedFullSync());

            // Lower epoch is rejected
            ReplicaDeltaRequest oldEpoch = ReplicaDeltaRequest.upsert("peer-a", epoch - 1, 99L, 0L, 15L,
                    new ReplicaRecord("svc", "i3", ip, 1, Collections.<String, Serializable>emptyMap()));
            oldEpoch.setAuthTag(NameserverReplicaProtocol.signDelta(CLUSTER_TOKEN, oldEpoch));
            ReplicaAck oldAck = server.replicaApplyDelta(oldEpoch);
            assertFalse(oldAck.isAccepted());
            assertTrue(server.discover("svc").isEmpty());
        } finally {
            server.close();
        }
    }

    @Test
    @Timeout(20)
    void unauthorizedPeerAndWrongTokenAreRejected() throws Exception {
        int dnsPort = freePort(), regPort = freePort();
        NameserverConfig cfg = baseConfig("ns-auth", dnsPort, regPort, null, CLUSTER_TOKEN);
        cfg.setAuthorizedReplicaPeers(Collections.singleton("allowed-peer"));
        NameserverImpl server = new NameserverImpl(cfg);
        Nameserver rpcClient = newClient(regPort);
        try {
            InetAddress ip = InetAddress.getByName("127.0.0.5");

            // 1. Wrong auth token over real RPC
            ReplicaDeltaRequest wrongToken = ReplicaDeltaRequest.upsert("allowed-peer", 10L, 1L, 0L, 1L,
                    new ReplicaRecord("secret-app", "x1", ip, 1, Collections.<String, Serializable>emptyMap()));
            wrongToken.setAuthTag(NameserverReplicaProtocol.signDelta("wrong-secret", wrongToken));
            assertThrows(RemotingException.class, () -> rpcClient.replicaApplyDelta(wrongToken));
            assertTrue(server.discover("secret-app").isEmpty());

            // 2. Valid token but unauthorized peer sourceId (and 127.0.0.1 is not in allowlist)
            ReplicaDeltaRequest unauthorizedPeer = ReplicaDeltaRequest.upsert("rogue-peer", 10L, 1L, 0L, 2L,
                    new ReplicaRecord("secret-app", "x2", ip, 1, Collections.<String, Serializable>emptyMap()));
            unauthorizedPeer.setAuthTag(NameserverReplicaProtocol.signDelta(CLUSTER_TOKEN, unauthorizedPeer));
            assertThrows(RemotingException.class, () -> rpcClient.replicaApplyDelta(unauthorizedPeer));
            assertTrue(server.discover("secret-app").isEmpty());

            // 3. Valid token AND authorized sourceId succeeds
            ReplicaDeltaRequest valid = ReplicaDeltaRequest.upsert("allowed-peer", 10L, 1L, 0L, 3L,
                    new ReplicaRecord("secret-app", "x3", ip, 1, Collections.<String, Serializable>emptyMap()));
            valid.setAuthTag(NameserverReplicaProtocol.signDelta(CLUSTER_TOKEN, valid));
            ReplicaAck ack = rpcClient.replicaApplyDelta(valid);
            assertTrue(ack.isAccepted());
            assertEquals(Collections.singletonList(ip), server.discover("secret-app"));
        } finally {
            rpcClient.close();
            server.close();
        }
    }

    @Test
    @Timeout(20)
    void unreachablePeerDoesNotBlockHealthyPeerReplication() throws Exception {
        int dns1 = freePort(), reg1 = freePort();
        int dns2 = freePort(), reg2 = freePort();
        int deadPort = freePort(); // nobody listening here
        NameserverImpl n1 = newServer("ns-fast-1", dns1, reg1,
                Arrays.asList("127.0.0.1:" + deadPort, "127.0.0.1:" + reg2), CLUSTER_TOKEN);
        NameserverImpl n2 = newServer("ns-fast-2", dns2, reg2,
                Collections.singletonList("127.0.0.1:" + reg1), CLUSTER_TOKEN);
        Nameserver c1 = newClient(reg1);
        try {
            c1.register("fast-app", RandomList.DEFAULT_WEIGHT, "f-1", Collections.<InetSocketAddress>emptySet());
            awaitTrue(() -> instanceIds(n2, "fast-app").contains("f-1"),
                    6000, "healthy peer n2 must converge quickly despite unreachable peer");
        } finally {
            c1.close();
            n1.close();
            n2.close();
        }
    }

    @Test
    @Timeout(20)
    void disposeShouldCancelHealthTask() throws Exception {
        NameserverClient client = new NameserverClient("dispose-health");
        NameserverClient.NsInfo tuple = new NameserverClient.NsInfo(new InetSocketAddress("127.0.0.1", freePort()));
        ScheduledFuture<?> future = org.rx.core.Tasks.schedulePeriod(() -> {
        }, 60_000L);
        tuple.healthTask = future;
        client.holder.add(tuple);

        client.close();

        assertTrue(future.isCancelled(), "NameserverClient close should cancel healthTask");
    }

    @Test
    @Timeout(20)
    void replicaEndpointShouldResolveUnresolvedIpLiteral() throws Exception {
        NameserverImpl server = newServer("ns-res", freePort(), freePort(), null, CLUSTER_TOKEN);
        try {
            Set<InetSocketAddress> endpoints = new HashSet<InetSocketAddress>();
            endpoints.add(InetSocketAddress.createUnresolved("127.0.0.1", 854));

            Set<InetSocketAddress> resolved = server.resolveReplicaEndpoints(endpoints);

            assertEquals(1, resolved.size());
            assertFalse(resolved.iterator().next().isUnresolved());
            assertEquals(InetAddress.getByName("127.0.0.1"), resolved.iterator().next().getAddress());
        } finally {
            server.close();
        }
    }

    @Test
    @Timeout(20)
    void replicaSelfEndpointShouldMatchLocalAddresses() throws Exception {
        int registerPort = freePort();
        NameserverImpl server = newServer("ns-self", freePort(), registerPort, null, CLUSTER_TOKEN);
        try {
            assertTrue(server.isLocalReplicaEndpoint(new InetSocketAddress(InetAddress.getLoopbackAddress(), registerPort)));
            assertFalse(server.isLocalReplicaEndpoint(new InetSocketAddress(InetAddress.getLoopbackAddress(), registerPort + 1)));
            assertFalse(server.isLocalReplicaEndpoint(new InetSocketAddress(InetAddress.getByAddress(new byte[]{8, 8, 8, 8}), registerPort)));

            List<Inet4Address> localAddresses = Sockets.getLocalAddresses(false);
            if (!localAddresses.isEmpty()) {
                assertTrue(server.isLocalReplicaEndpoint(new InetSocketAddress(localAddresses.get(0), registerPort)));
            }
        } finally {
            server.close();
        }
    }

    @Test
    @Timeout(20)
    void registersHttpPageOnDefaultHttpServer() throws Exception {
        RxConfig.HttpConfig httpConfig = RxConfig.INSTANCE.getNet().getHttp();
        int oldPort = httpConfig.getServerPort();
        boolean oldTls = httpConfig.isServerTls();
        int httpPort = freePort();
        NameserverImpl server = null;
        try {
            httpConfig.setServerPort(httpPort);
            httpConfig.setServerTls(false);

            server = newServer("ns-http", freePort(), freePort(), null, CLUSTER_TOKEN);
            HttpServer httpServer = HttpServer.getDefault();

            assertNotNull(httpServer);
            assertTrue(httpServer.getMapping().containsKey(NameserverHttpHandler.PAGE_PATH));
        } finally {
            if (server != null) {
                server.close();
            }
            HttpServer httpServer = HttpServer.getDefault();
            if (httpServer != null) {
                httpServer.close();
            }
            httpConfig.setServerPort(oldPort);
            httpConfig.setServerTls(oldTls);
        }
    }

    @Test
    @Timeout(20)
    void constructorReusesExternalDnsServer() throws Exception {
        int dnsPort = freePort();
        int registerPort = freePort();
        DnsServer dnsServer = new DnsServer(dnsPort, Collections.emptyList());
        NameserverImpl server = null;
        try {
            NameserverConfig config = baseConfig("ns-ext-dns", dnsPort, registerPort, null, CLUSTER_TOKEN);
            server = new NameserverImpl(config, dnsServer);

            assertSame(dnsServer, server.getDnsServer());
        } finally {
            if (server != null) {
                server.close();
            }
            dnsServer.close();
        }
    }

    static NameserverConfig baseConfig(String nodeId, int dnsPort, int registerPort,
                                       List<String> replicaEndpoints, String token) {
        NameserverConfig config = new NameserverConfig();
        config.setNodeId(nodeId);
        config.setDnsPort(dnsPort);
        config.setRegisterPort(registerPort);
        config.setReplicaEndpoints(replicaEndpoints);
        config.setReplicaAuthToken(token);
        config.setReplicaFullSyncPeriodMillis(0);
        config.setReplicaSyncTimeoutMillis(1500);
        config.setReplicaRetryBaseMillis(100L);
        config.setReplicaRetryMaxMillis(400L);
        return config;
    }

    static NameserverImpl newServer(String nodeId, int dnsPort, int registerPort,
                                    List<String> replicaEndpoints, String token) {
        return new NameserverImpl(baseConfig(nodeId, dnsPort, registerPort, replicaEndpoints, token));
    }

    static Nameserver newClient(int registerPort) {
        RpcClientConfig<Nameserver> config = RpcClientConfig.statefulMode(new InetSocketAddress("127.0.0.1", registerPort), 0);
        config.getTcpConfig().setConnectTimeoutMillis(1500);
        config.getTcpConfig().setWaitConnectMillis(500);
        return Remoting.createFacade(Nameserver.class, config);
    }

    static Set<String> instanceIds(NameserverImpl server, String appName) {
        Set<String> ids = new HashSet<String>();
        for (Nameserver.InstanceInfo info : server.discoverAll(appName, false, Collections.<String>emptyList())) {
            if (info.getInstanceId() != null) {
                ids.add(info.getInstanceId());
            }
        }
        return ids;
    }

    static void assertContainsLoopback(List<InetAddress> hosts) {
        assertTrue(hosts.contains(InetAddress.getLoopbackAddress()), "nameserver should contain loopback address");
    }

    static void awaitTrue(java.util.function.BooleanSupplier condition, long timeoutMillis, String message) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(50);
        }
        assertTrue(condition.getAsBoolean(), message);
    }

    static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
