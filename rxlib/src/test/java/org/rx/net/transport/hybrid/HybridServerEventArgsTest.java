package org.rx.net.transport.hybrid;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.rx.net.transport.TcpServerConfig;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HybridServerEventArgsTest {
    @Test
    @Timeout(10)
    void serverReceiveCarriesSessionAndCanReply() throws Exception {
        int port = freePort();
        HybridConfig serverConfig = new HybridConfig();
        serverConfig.setTcpServerConfig(new TcpServerConfig(port));
        serverConfig.setEnableUdpDirect(false);

        HybridConfig clientConfig = new HybridConfig();
        clientConfig.setEnableUdpDirect(false);

        CountDownLatch serverReceived = new CountDownLatch(1);
        CountDownLatch clientReceived = new CountDownLatch(1);
        HybridSession[] source = new HybridSession[1];
        String[] reply = new String[1];
        try (HybridServer server = new HybridServer(serverConfig);
             HybridClient client = new HybridClient(clientConfig)) {
            server.onReceive.combine((s, e) -> {
                source[0] = e.getSession();
                e.getSession().send("pong");
                serverReceived.countDown();
            });
            client.onReceive.combine((s, e) -> {
                reply[0] = (String) e.getValue();
                clientReceived.countDown();
            });

            server.start();
            client.connect(new InetSocketAddress("127.0.0.1", port));
            client.send("ping");

            assertTrue(serverReceived.await(3, TimeUnit.SECONDS));
            assertTrue(clientReceived.await(3, TimeUnit.SECONDS));
            assertNotNull(source[0]);
            assertEquals("pong", reply[0]);
            assertEquals(source[0], server.getSession(((DefaultHybridSession) source[0]).sessionId));
        }
    }

    static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
