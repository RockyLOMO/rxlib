package org.rx.net.rpc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.rx.net.transport.TcpServerConfig;
import org.rx.net.transport.core.TransportClient;
import org.rx.net.transport.core.TransportServer;
import org.rx.test.PersonBean;
import org.rx.test.UserEventArgs;
import org.rx.test.UserManager;
import org.rx.test.UserManagerImpl;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RemotingUdpEventRouteTest {
    @Test
    @Timeout(15)
    void rpcUsesDailyAdaptiveUdpWhenEnabled() throws Exception {
        int port = freePort();
        UserManagerImpl service = new UserManagerImpl();
        RpcServerConfig serverConfig = new RpcServerConfig(new TcpServerConfig(port));
        serverConfig.getTransportConfig().setEnableAdaptiveUdp(true);

        TransportServer server = Remoting.register(service, serverConfig);
        AtomicReference<TransportClient> clientRef = new AtomicReference<TransportClient>();
        RpcClientConfig<UserManager> clientConfig = RpcClientConfig.statefulMode(new InetSocketAddress("127.0.0.1", port), 0);
        clientConfig.getTransportConfig().setEnableAdaptiveUdp(true);
        clientConfig.setInitHandler((p, c) -> clientRef.set(c));
        UserManager facade = Remoting.createFacade(UserManager.class, clientConfig);
        try {
            assertTrue(server.udpPort() > 0, "Daily adaptive RPC server must bind single UDP port");
            assertEquals(2, facade.computeLevel(1, 1));
            assertNotNull(clientRef.get());
            assertNotNull(clientRef.get().session());
            assertTrue(clientRef.get().session().isAdaptiveUdpNegotiated());

            awaitTrue(() -> {
                assertEquals(5, facade.computeLevel(2, 3));
                return clientRef.get().session().isUsingUdp();
            }, 5000, "Daily adaptive RPC session should promote to UDP fast path");

            CountDownLatch eventLatch = new CountDownLatch(1);
            facade.<UserEventArgs>attachEvent("udpRoute", (s, e) -> eventLatch.countDown(), false);
            facade.publishEvent("udpRoute", new UserEventArgs(PersonBean.LeZhi));
            assertTrue(eventLatch.await(5, TimeUnit.SECONDS));
        } finally {
            facade.close();
            server.close();
        }
    }

    @Test
    @Timeout(15)
    void rpcRunsPureTcpWithZeroUdpSocketsWhenAdaptiveDisabled() throws Exception {
        int port = freePort();
        UserManagerImpl service = new UserManagerImpl();
        RpcServerConfig serverConfig = new RpcServerConfig(new TcpServerConfig(port));
        serverConfig.getTransportConfig().setEnableAdaptiveUdp(false);

        TransportServer server = Remoting.register(service, serverConfig);
        AtomicReference<TransportClient> clientRef = new AtomicReference<TransportClient>();
        RpcClientConfig<UserManager> clientConfig = RpcClientConfig.statefulMode(new InetSocketAddress("127.0.0.1", port), 0);
        clientConfig.getTransportConfig().setEnableAdaptiveUdp(false);
        clientConfig.setInitHandler((p, c) -> clientRef.set(c));
        UserManager facade = Remoting.createFacade(UserManager.class, clientConfig);
        try {
            assertEquals(0, server.udpPort(), "Pure TCP RPC server must bind zero UDP sockets");
            assertEquals(2, facade.computeLevel(1, 1));
            assertNotNull(clientRef.get());
            assertNotNull(clientRef.get().session());
            assertFalse(clientRef.get().session().isAdaptiveUdpNegotiated());
            assertFalse(clientRef.get().session().isUsingUdp());

            CountDownLatch eventLatch = new CountDownLatch(1);
            facade.<UserEventArgs>attachEvent("tcpRoute", (s, e) -> eventLatch.countDown(), false);
            facade.publishEvent("tcpRoute", new UserEventArgs(PersonBean.LeZhi));
            assertTrue(eventLatch.await(5, TimeUnit.SECONDS));
            assertFalse(clientRef.get().session().isUsingUdp());
        } finally {
            facade.close();
            server.close();
        }
    }

    static void awaitTrue(BooleanSupplier condition, long timeoutMillis, String message) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(30);
        }
        assertTrue(condition.getAsBoolean(), message);
    }

    static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    interface BooleanSupplier {
        boolean getAsBoolean();
    }
}
