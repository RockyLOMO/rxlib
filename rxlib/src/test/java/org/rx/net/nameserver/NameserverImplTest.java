package org.rx.net.nameserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.rx.net.rpc.Remoting;
import org.rx.net.rpc.RpcClientConfig;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.Collections;
import java.util.List;
import java.util.Set;

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

    static NameserverImpl newServer(int dnsPort, int registerPort, int syncPort) {
        NameserverConfig config = new NameserverConfig();
        config.setDnsPort(dnsPort);
        config.setRegisterPort(registerPort);
        config.setSyncPort(syncPort);
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
