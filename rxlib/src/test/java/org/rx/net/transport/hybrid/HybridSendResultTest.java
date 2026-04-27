package org.rx.net.transport.hybrid;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.rx.net.transport.TcpServerConfig;
import org.rx.net.transport.UdpClientConfig;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HybridSendResultTest {
    @Test
    @Timeout(10)
    void tcpOnlySendResultReportsRouteAndSequence() throws Exception {
        int port = freePort();
        HybridConfig serverConfig = new HybridConfig();
        serverConfig.setTcpServerConfig(new TcpServerConfig(port));
        serverConfig.setEnableUdpDirect(false);

        HybridConfig clientConfig = new HybridConfig();
        clientConfig.setEnableUdpDirect(false);

        CountDownLatch received = new CountDownLatch(1);
        try (HybridServer server = new HybridServer(serverConfig);
             HybridClient client = new HybridClient(clientConfig)) {
            server.onReceive.combine((s, e) -> received.countDown());

            server.start();
            client.connect(new InetSocketAddress("127.0.0.1", port));
            HybridSendResult result = client.session().sendWithResult("tcp-result", HybridSendOptions.DEFAULT);

            assertFalse(result.isCancelled());
            assertEquals(HybridRoute.TCP, result.getSelectedRoute());
            assertEquals(HybridRoute.TCP, result.getActualRoute());
            assertTrue(result.getSequence() > 0);
            assertTrue(result.getEncodedBytes() > 0);
            assertTrue(result.getWriteFuture().isDone());
            assertTrue(result.getAckFuture().isDone());
            assertTrue(received.await(3, TimeUnit.SECONDS));
        }
    }

    @Test
    @Timeout(10)
    void udpAckTimeoutFallbackReportsTcpActualRoute() throws Exception {
        int port = freePort();
        int unusedUdpPort = freePort();
        HybridConfig serverConfig = new HybridConfig();
        serverConfig.setTcpServerConfig(new TcpServerConfig(port));
        serverConfig.setEnableUdpDirect(false);

        UdpClientConfig udpClientConfig = new UdpClientConfig();
        udpClientConfig.setMaxResend(0);
        HybridConfig clientConfig = new HybridConfig();
        clientConfig.setUdpClientConfig(udpClientConfig);
        clientConfig.setEnableUdpDirect(false);
        clientConfig.setUdpAckTimeoutMillis(80);

        CountDownLatch received = new CountDownLatch(1);
        try (HybridServer server = new HybridServer(serverConfig);
             HybridClient client = new HybridClient(clientConfig)) {
            server.onReceive.combine((s, e) -> {
                if ("udp-fallback".equals(e.getValue())) {
                    received.countDown();
                }
            });

            server.start();
            client.connect(new InetSocketAddress("127.0.0.1", port));
            DefaultHybridSession session = (DefaultHybridSession) client.session();
            session.udpRemoteEndpoint = new InetSocketAddress("127.0.0.1", unusedUdpPort);
            session.setRouteState(HybridRouteState.UDP_READY, "test-force-udp");

            HybridSendOptions options = new HybridSendOptions();
            options.setWaitAckTimeoutMillis(80);
            HybridSendResult result = session.sendWithResult("udp-fallback", options);

            result.getAckFuture().get(3, TimeUnit.SECONDS);
            assertEquals(HybridRoute.UDP, result.getSelectedRoute());
            assertEquals(HybridRoute.TCP, result.getActualRoute());
            assertTrue(received.await(3, TimeUnit.SECONDS));
        }
    }

    static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
