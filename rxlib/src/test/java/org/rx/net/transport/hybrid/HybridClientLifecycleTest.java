package org.rx.net.transport.hybrid;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.rx.net.transport.TcpServerConfig;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
            client.onSessionReady.add((s, e) -> ready.countDown());

            server.start();
            client.connectAsync(new InetSocketAddress("127.0.0.1", port)).get(3, TimeUnit.SECONDS);

            assertTrue(ready.await(3, TimeUnit.SECONDS));
            assertTrue(client.isSessionReady());
            assertNotNull(client.session());
        }
    }

    @Test
    @Timeout(10)
    void completeConnectCompletesExceptionallyWhenHelloSendFails() throws Exception {
        HybridConfig clientConfig = new HybridConfig();
        clientConfig.setEnableUdpDirect(false);

        try (HybridClient client = new HybridClient(clientConfig)) {
            CompletableFuture<Void> promise = new CompletableFuture<Void>();
            client.completeConnect(promise, null);

            assertTrue(promise.isCompletedExceptionally());
            assertThrows(ExecutionException.class, () -> promise.get(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void udpEndpointRejectsHostnamesWithoutDns() throws Exception {
        InetAddress loopback = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
        InetSocketAddress tcpRemote = new InetSocketAddress(loopback, 1);

        assertNull(HybridClient.udpEndpoint("not-a-literal.invalid", 53, tcpRemote));

        InetSocketAddress wildcard = HybridClient.udpEndpoint("0.0.0.0", 1234, tcpRemote);
        assertNotNull(wildcard);
        assertEquals(loopback, wildcard.getAddress());
        assertEquals(1234, wildcard.getPort());

        InetSocketAddress literal = HybridClient.udpEndpoint("127.0.0.1", 2345, null);
        assertNotNull(literal);
        assertEquals(loopback, literal.getAddress());
        assertEquals(2345, literal.getPort());
    }

    static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
