package org.rx.net.punch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UdpHolePunchIntegrationTest {
    @Test
    @Timeout(15)
    void connectBuildsDirectSessionAndSupportsRpc() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (UdpHolePunchServer server = new UdpHolePunchServer(0);
             UdpHolePunchClient clientA = new UdpHolePunchClient(0);
             UdpHolePunchClient clientB = new UdpHolePunchClient(0)) {
            InetSocketAddress serverEndpoint = server.getLocalEndpoint();
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
            sessionB.onReceive.add((s, e) -> {
                if ("direct-ping".equals(e.getValue().packet())) {
                    receiveLatch.countDown();
                    s.reply(e.getValue(), "direct-pong");
                }
            });

            assertEquals("peer-b", sessionA.getRemotePeerId());
            assertEquals("peer-a", sessionB.getRemotePeerId());
            assertEquals(clientB.getLocalEndpoint().getPort(), sessionA.getDirectRemoteEndpoint().getPort());
            assertEquals(clientA.getLocalEndpoint().getPort(), sessionB.getDirectRemoteEndpoint().getPort());
            assertTrue(sessionA.getDirectRemoteEndpoint().getAddress().isLoopbackAddress());
            assertTrue(sessionB.getDirectRemoteEndpoint().getAddress().isLoopbackAddress());
            assertEquals("direct-pong", sessionA.request("direct-ping", String.class, 3000));
            assertTrue(receiveLatch.await(3, TimeUnit.SECONDS), "对端应收到直连报文");
        } finally {
            pool.shutdownNow();
        }
    }
}
