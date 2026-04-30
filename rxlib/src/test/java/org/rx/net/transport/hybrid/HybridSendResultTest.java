package org.rx.net.transport.hybrid;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.rx.net.transport.FuryUdpClientCodec;
import org.rx.net.transport.TcpServerConfig;
import org.rx.net.transport.UdpClientCodec;
import org.rx.net.transport.UdpClientConfig;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
        CountingCodec clientCodec = new CountingCodec();
        UdpClientConfig clientUdpConfig = new UdpClientConfig();
        clientUdpConfig.setCodec(clientCodec);
        clientConfig.setUdpClientConfig(clientUdpConfig);

        CountDownLatch received = new CountDownLatch(1);
        try (HybridServer server = new HybridServer(serverConfig);
             HybridClient client = new HybridClient(clientConfig)) {
            server.onReceive.add((s, e) -> received.countDown());

            server.start();
            client.connect(new InetSocketAddress("127.0.0.1", port));
            clientCodec.encodeCount.set(0);
            HybridSendResult result = client.session().sendWithResult("tcp-result", HybridSendOptions.DEFAULT);

            assertFalse(result.isCancelled());
            assertEquals(HybridRoute.TCP, result.getSelectedRoute());
            assertEquals(HybridRoute.TCP, result.getActualRoute());
            assertTrue(result.getSequence() > 0);
            assertEquals(DefaultHybridSession.UNKNOWN_ENCODED_BYTES, result.getEncodedBytes());
            assertTrue(result.getWriteFuture().isDone());
            assertTrue(result.getAckFuture().isDone());
            assertTrue(received.await(3, TimeUnit.SECONDS));
            assertEquals(1, clientCodec.encodeCount.get());
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
            server.onReceive.add((s, e) -> {
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

    static final class CountingCodec implements UdpClientCodec {
        private static final long serialVersionUID = -7318040175316049087L;

        final FuryUdpClientCodec delegate = FuryUdpClientCodec.createDefault();
        final AtomicInteger encodeCount = new AtomicInteger();

        @Override
        public ByteBuf encode(ByteBufAllocator allocator, Object packet) throws Exception {
            encodeCount.incrementAndGet();
            return delegate.encode(allocator, packet);
        }

        @Override
        public Object decode(ByteBuf payload) throws Exception {
            return delegate.decode(payload);
        }
    }
}
