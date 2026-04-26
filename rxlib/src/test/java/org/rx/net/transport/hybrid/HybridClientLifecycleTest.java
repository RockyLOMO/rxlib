package org.rx.net.transport.hybrid;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.rx.net.transport.TcpServerConfig;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HybridClientLifecycleTest {
    @Test
    @Timeout(10)
    void connectAsyncCreatesReadySession() throws Exception {
        int port = freePort();
        HybridConfig serverConfig = new HybridConfig();
        serverConfig.setTcpServerConfig(new TcpServerConfig(port));
        serverConfig.setEnableUdpDirect(false);

        HybridConfig clientConfig = new HybridConfig();
        clientConfig.setEnableUdpDirect(false);

        CountDownLatch ready = new CountDownLatch(1);
        try (HybridServer server = new HybridServer(serverConfig);
             HybridClient client = new HybridClient(clientConfig)) {
            client.onSessionReady.combine((s, e) -> ready.countDown());

            server.start();
            client.connectAsync(new InetSocketAddress("127.0.0.1", port)).get(3, TimeUnit.SECONDS);

            assertTrue(ready.await(3, TimeUnit.SECONDS));
            assertTrue(client.isSessionReady());
            assertNotNull(client.session());
        }
    }

    static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
