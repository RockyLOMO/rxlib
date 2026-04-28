package org.rx.net.transport.hybrid;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.rx.net.transport.TcpServerConfig;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HybridTransportUdpDirectIntegrationTest {
    @Test
    @Timeout(10)
    void smallPacketUsesUdpAfterProbe() throws Exception {
        int port = freePort();
        HybridConfig serverConfig = new HybridConfig();
        serverConfig.setTcpServerConfig(new TcpServerConfig(port));
        serverConfig.setUdpSmallPacketThresholdBytes(2048);

        HybridConfig clientConfig = new HybridConfig();
        clientConfig.setUdpSmallPacketThresholdBytes(2048);

        CountDownLatch received = new CountDownLatch(1);
        String[] value = new String[1];
        try (HybridServer server = new HybridServer(serverConfig);
             HybridClient client = new HybridClient(clientConfig)) {
            server.onReceive.add((s, e) -> {
                value[0] = (String) e.getValue();
                received.countDown();
            });

            server.start();
            client.connect(new InetSocketAddress("127.0.0.1", port));
            awaitTrue(() -> client.session() != null && client.session().routeState() == HybridRouteState.UDP_READY,
                    3000, "UDP direct probe should complete");

            client.send("udp-small");

            assertTrue(received.await(3, TimeUnit.SECONDS));
            assertEquals("udp-small", value[0]);
            assertTrue(server.getMetrics().udpReceivePackets() >= 1, "small packet should arrive on UDP");
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
