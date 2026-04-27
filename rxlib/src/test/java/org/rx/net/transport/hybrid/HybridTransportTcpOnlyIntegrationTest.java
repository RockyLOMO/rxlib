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

class HybridTransportTcpOnlyIntegrationTest {
    @Test
    @Timeout(10)
    void tcpOnlySendAndReceive() throws Exception {
        int port = freePort();
        HybridConfig serverConfig = new HybridConfig();
        serverConfig.setTcpServerConfig(new TcpServerConfig(port));
        serverConfig.setEnableUdpDirect(false);

        HybridConfig clientConfig = new HybridConfig();
        clientConfig.setEnableUdpDirect(false);

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
            client.send("tcp-only");

            assertTrue(received.await(3, TimeUnit.SECONDS));
            assertEquals("tcp-only", value[0]);
            assertEquals(1, server.getMetrics().tcpReceivePackets());
        }
    }

    static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
