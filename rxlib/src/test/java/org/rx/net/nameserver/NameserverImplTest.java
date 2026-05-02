package org.rx.net.nameserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.rx.core.RxConfig;
import org.rx.net.dns.DnsServer;
import org.rx.net.http.HttpServer;
import org.rx.net.rpc.Remoting;
import org.rx.net.rpc.RpcClientConfig;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.rx.core.Extends.sleep;

class NameserverImplTest {
    @Test
    @Timeout(20)
    void registerAndDeregisterUsesHybridSessionAttr() throws Exception {
        int dnsPort = freePort();
        int registerPort = freePort();
        int syncPort = freePort();
        NameserverImpl server = newServer(dnsPort, registerPort, syncPort);
        Nameserver client = newClient(registerPort);
        try {
            Set<InetSocketAddress> endpoints = Collections.singleton(new InetSocketAddress("127.0.0.1", registerPort));

            client.register("order", endpoints);
            assertContainsLoopback(server.discover("order"));

            client.deregister();

            awaitEmpty(server, "order", 3000);
        } finally {
            client.close();
            server.close();
        }
    }

    @Test
    @Timeout(20)
    void clientDisconnectDeregistersBeforeHybridAttrsAreCleared() throws Exception {
        int dnsPort = freePort();
        int registerPort = freePort();
        int syncPort = freePort();
        NameserverImpl server = newServer(dnsPort, registerPort, syncPort);
        Nameserver client = newClient(registerPort);
        try {
            Set<InetSocketAddress> endpoints = Collections.singleton(new InetSocketAddress("127.0.0.1", registerPort));

            client.register("billing", endpoints);
            assertContainsLoopback(server.discover("billing"));

            client.close();

            awaitEmpty(server, "billing", 5000);
        } finally {
            server.close();
        }
    }

    @Test
    @Timeout(20)
    void nameserverClientRegistersPublicIpAttr() throws Exception {
        int dnsPort = freePort();
        int registerPort = freePort();
        int syncPort = freePort();
        NameserverImpl server = newServer(dnsPort, registerPort, syncPort);
        NameserverClient client = new NameserverClient("inventory");
        client.publicIpResolver = () -> "1.1.1.1";
        try {
            client.registerAsync(Collections.singleton(new InetSocketAddress("127.0.0.1", registerPort))).get(15, TimeUnit.SECONDS);

            List<Nameserver.InstanceInfo> infos = server.discover("inventory", Collections.singletonList(Nameserver.PUBLIC_IP_KEY));

            assertEquals("1.1.1.1", infos.get(0).getAttributes().get(Nameserver.PUBLIC_IP_KEY));
        } finally {
            client.close();
            server.close();
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

        assertTrue(future.isCancelled(), "NameserverClient close 应取消 healthTask");
    }

    @Test
    @Timeout(20)
    void replicaSyncShouldUseAttrsSnapshot() throws Exception {
        NameserverImpl server = newServer(freePort(), freePort(), freePort());
        try {
            Map<String, Serializable> appAttrs = server.attrs("app");
            appAttrs.put("version", "v1");

            Map<Object, Map<String, Serializable>> snapshot = server.snapshotAttrs();
            appAttrs.put("version", "v2");

            assertEquals("v1", snapshot.get("app").get("version"));
        } finally {
            server.close();
        }
    }

    @Test
    @Timeout(20)
    void replicaFullSyncShouldApplyHostsSnapshot() throws Exception {
        NameserverImpl server = newServer(freePort(), freePort(), freePort());
        try {
            InetAddress oldIp = InetAddress.getByName("127.0.0.1");
            InetAddress newIp = InetAddress.getByName("127.0.0.2");
            server.getDnsServer().addHosts("old-app", Collections.singletonList(oldIp.getHostAddress()).toArray(new String[0]));
            Map<String, List<InetAddress>> hosts = new HashMap<String, List<InetAddress>>();
            hosts.put("new-app", Collections.singletonList(newIp));

            server.applyFullSync(new NameserverImpl.ReplicaFullSync(1L, Collections.<InetSocketAddress>emptySet(),
                    hosts, Collections.<Object, Map<String, Serializable>>emptyMap()));

            assertTrue(server.discover("old-app").isEmpty());
            assertEquals(newIp, server.discover("new-app").get(0));
        } finally {
            server.close();
        }
    }

    @Test
    @Timeout(20)
    void instanceAttrsShouldNotConflictBySameIpDifferentApp() throws Exception {
        NameserverImpl server = newServer(freePort(), freePort(), freePort());
        try {
            InetAddress loop = InetAddress.getLoopbackAddress();
            server.getDnsServer().addHosts("app-a", 1, Collections.singletonList(loop));
            server.getDnsServer().addHosts("app-b", 1, Collections.singletonList(loop));
            server.attrs(server.instanceKey("app-a", loop)).put("zone", "a");
            server.attrs(server.instanceKey("app-b", loop)).put("zone", "b");

            assertEquals("a", server.discover("app-a", Collections.singletonList("zone")).get(0).getAttributes().get("zone"));
            assertEquals("b", server.discover("app-b", Collections.singletonList("zone")).get(0).getAttributes().get("zone"));
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

            server = newServer(freePort(), freePort(), freePort());
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
        int syncPort = freePort();
        DnsServer dnsServer = new DnsServer(dnsPort, Collections.emptyList());
        NameserverImpl server = null;
        try {
            NameserverConfig config = new NameserverConfig();
            config.setDnsPort(dnsPort);
            config.setRegisterPort(registerPort);
            config.setSyncPort(syncPort);

            server = new NameserverImpl(config, dnsServer);

            assertSame(dnsServer, server.getDnsServer());
        } finally {
            if (server != null) {
                server.close();
            }
            dnsServer.close();
        }
    }

    static NameserverImpl newServer(int dnsPort, int registerPort, int syncPort) {
        NameserverConfig config = new NameserverConfig();
        config.setDnsPort(dnsPort);
        config.setRegisterPort(registerPort);
        config.setSyncPort(syncPort);
        config.setReplicaFullSyncPeriodMillis(0);
        return new NameserverImpl(config);
    }

    static Nameserver newClient(int registerPort) {
        RpcClientConfig<Nameserver> config = RpcClientConfig.statefulMode(new InetSocketAddress("127.0.0.1", registerPort), 0);
        config.getTcpConfig().setConnectTimeoutMillis(1000);
        config.getTcpConfig().setWaitConnectMillis(300);
        return Remoting.createFacade(Nameserver.class, config);
    }

    static void assertContainsLoopback(List<InetAddress> hosts) {
        assertTrue(hosts.contains(InetAddress.getLoopbackAddress()), "nameserver 应注册 loopback 地址");
    }

    static void awaitEmpty(NameserverImpl server, String appName, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (server.discover(appName).isEmpty()) {
                return;
            }
            sleep(50);
        }
        assertTrue(server.discover(appName).isEmpty(), "nameserver 应清理 " + appName);
    }

    static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
