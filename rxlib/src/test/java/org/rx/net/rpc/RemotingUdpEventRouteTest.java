package org.rx.net.rpc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.rx.net.transport.TcpServerConfig;
import org.rx.net.transport.hybrid.HybridClient;
import org.rx.net.transport.hybrid.HybridRouteState;
import org.rx.net.transport.hybrid.HybridServer;
import org.rx.test.PersonBean;
import org.rx.test.UserEventArgs;
import org.rx.test.UserManager;
import org.rx.test.UserManagerImpl;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemotingUdpEventRouteTest {
    @Test
    @Timeout(12)
    void publishEventUsesUdpWhenHybridRouteReady() throws Exception {
        int port = freePort();
        UserManagerImpl service = new UserManagerImpl();
        RpcServerConfig serverConfig = new RpcServerConfig(new TcpServerConfig(port));
        serverConfig.getHybridConfig().setUdpSmallPacketThresholdBytes(4096);

        HybridServer server = Remoting.registerHybrid(service, serverConfig);
        AtomicReference<HybridClient> clientRef = new AtomicReference<HybridClient>();
        RpcClientConfig<UserManager> clientConfig = RpcClientConfig.statefulMode(new InetSocketAddress("127.0.0.1", port), 0);
        clientConfig.getHybridConfig().setUdpSmallPacketThresholdBytes(4096);
        clientConfig.setInitHandler((p, c) -> clientRef.set(c));
        UserManager facade = Remoting.createFacade(UserManager.class, clientConfig);
        try {
            assertEquals(2, facade.computeLevel(1, 1));
            awaitTrue(() -> clientRef.get() != null
                            && clientRef.get().session() != null
                            && clientRef.get().session().routeState() == HybridRouteState.UDP_READY,
                    5000, "client hybrid route should become UDP_READY");

            long beforeUdp = server.getMetrics().udpReceivePackets();
            facade.publishEvent("udpRoute", new UserEventArgs(PersonBean.LeZhi));

            awaitTrue(() -> server.getMetrics().udpReceivePackets() > beforeUdp,
                    3000, "publish event should arrive on UDP");
        } finally {
            facade.close();
            server.close();
        }
    }

    @Test
    @Timeout(12)
    void publishEventFallsBackToTcpWhenUdpRouteDisabled() throws Exception {
        int port = freePort();
        UserManagerImpl service = new UserManagerImpl();
        RpcServerConfig serverConfig = new RpcServerConfig(new TcpServerConfig(port));
        serverConfig.getHybridConfig().setEnableUdpDirect(false);
        serverConfig.getHybridConfig().setEnableUdpHolePunch(false);

        HybridServer server = Remoting.registerHybrid(service, serverConfig);
        RpcClientConfig<UserManager> clientConfig = RpcClientConfig.statefulMode(new InetSocketAddress("127.0.0.1", port), 0);
        clientConfig.getHybridConfig().setEnableUdpDirect(false);
        clientConfig.getHybridConfig().setEnableUdpHolePunch(false);
        UserManager facade = Remoting.createFacade(UserManager.class, clientConfig);
        try {
            assertEquals(2, facade.computeLevel(1, 1));

            long beforeTcp = server.getMetrics().tcpReceivePackets();
            long beforeUdp = server.getMetrics().udpReceivePackets();
            facade.publishEvent("tcpRoute", new UserEventArgs(PersonBean.LeZhi));

            awaitTrue(() -> server.getMetrics().tcpReceivePackets() > beforeTcp,
                    3000, "publish event should fall back to TCP");
            assertEquals(beforeUdp, server.getMetrics().udpReceivePackets());
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
