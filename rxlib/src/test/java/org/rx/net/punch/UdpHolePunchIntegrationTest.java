package org.rx.net.punch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UdpHolePunchIntegrationTest {
    static int freeUdpPort() throws Exception {
        try (DatagramSocket socket = new DatagramSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Test
    @Timeout(15)
    void connectBuildsDirectSessionAndSupportsRpc() throws Exception {
        int serverPort = freeUdpPort();
        int portA = freeUdpPort();
        int portB = freeUdpPort();
        InetSocketAddress serverEndpoint = new InetSocketAddress("127.0.0.1", serverPort);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (UdpHolePunchServer server = new UdpHolePunchServer(serverPort);
             UdpHolePunchClient clientA = new UdpHolePunchClient(portA);
             UdpHolePunchClient clientB = new UdpHolePunchClient(portB)) {
            clientA.setPeerWaitTimeoutMillis(3000);
            clientB.setPeerWaitTimeoutMillis(3000);
            clientA.setDirectConnectTimeoutMillis(3000);
            clientB.setDirectConnectTimeoutMillis(3000);
            clientA.setDirectProbeCount(12);
            clientB.setDirectProbeCount(12);

            Future<UdpHolePunchSession> futureA = pool.submit(() -> clientA.connect(serverEndpoint, "room-1", "peer-a"));
            Future<UdpHolePunchSession> futureB = pool.submit(() -> clientB.connect(serverEndpoint, "room-1", "peer-b"));

            UdpHolePunchSession sessionA = futureA.get(10, TimeUnit.SECONDS);
            UdpHolePunchSession sessionB = futureB.get(10, TimeUnit.SECONDS);

            CountDownLatch receiveLatch = new CountDownLatch(1);
            sessionB.onReceive.combine((s, e) -> {
                if ("direct-ping".equals(e.getValue().packet())) {
                    receiveLatch.countDown();
                    s.reply(e.getValue(), "direct-pong");
                }
            });

            assertEquals("peer-b", sessionA.getRemotePeerId());
            assertEquals("peer-a", sessionB.getRemotePeerId());
            assertEquals(portB, sessionA.getDirectRemoteEndpoint().getPort());
            assertEquals(portA, sessionB.getDirectRemoteEndpoint().getPort());
            assertTrue(sessionA.getDirectRemoteEndpoint().getAddress().isLoopbackAddress());
            assertTrue(sessionB.getDirectRemoteEndpoint().getAddress().isLoopbackAddress());
            assertEquals("direct-pong", sessionA.request("direct-ping", String.class, 3000));
            assertTrue(receiveLatch.await(3, TimeUnit.SECONDS), "对端应收到直连报文");
        } finally {
            pool.shutdownNow();
        }
    }
}
