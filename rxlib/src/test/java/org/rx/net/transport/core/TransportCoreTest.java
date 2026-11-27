package org.rx.net.transport.core;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.rx.net.SocketConfig;
import org.rx.net.TransportFlags;
import org.rx.net.rss.udp.RssDatagramHandler;
import org.rx.net.transport.ClientDisconnectedException;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class TransportCoreTest {

    @Test
    @Timeout(20)
    void pureTcpMode_CreatesZeroUdpSocketsAndStreamsRawByteBufFrames() throws Exception {
        int port = freePort();
        TransportConfig serverConf = TransportConfig.pureTcpServer(port);
        TransportConfig clientConf = TransportConfig.pureTcpClient(new InetSocketAddress("127.0.0.1", port));

        BlockingQueue<byte[]> serverReceived = new LinkedBlockingQueue<byte[]>();
        BlockingQueue<byte[]> clientReceived = new LinkedBlockingQueue<byte[]>();
        AtomicReference<TransportSession> serverSessionRef = new AtomicReference<TransportSession>();

        try (TransportServer server = new TransportServer(serverConf);
             TransportClient client = new TransportClient(clientConf)) {
            server.onConnected.add((s, e) -> serverSessionRef.set(e.getValue()));
            server.setReceiveHandler((session, buf) -> {
                byte[] bytes = new byte[buf.readableBytes()];
                buf.readBytes(bytes);
                serverReceived.offer(bytes);
                // Echo back raw ByteBuf with 0xFF prefix
                ByteBuf reply = Unpooled.buffer(bytes.length + 1);
                reply.writeByte(0xFF).writeBytes(bytes);
                session.send(reply);
            });
            client.setReceiveHandler((session, buf) -> {
                byte[] bytes = new byte[buf.readableBytes()];
                buf.readBytes(bytes);
                clientReceived.offer(bytes);
            });

            server.start();
            assertEquals(0, server.udpPort(), "Pure TCP server must bind zero UDP sockets");

            client.connect(new InetSocketAddress("127.0.0.1", port));
            TransportSession clientSession = client.session();
            assertNotNull(clientSession);
            assertTrue(clientSession.isConnected());
            assertFalse(clientSession.isAdaptiveUdpNegotiated());
            assertFalse(clientSession.isUsingUdp());

            int[] sizes = new int[]{0, 1, 17, 1500, 65536, 200_000};
            for (int size : sizes) {
                byte[] payload = randomBytes(size);
                assertTrue(client.sendAsync(Unpooled.wrappedBuffer(payload)).await(5, TimeUnit.SECONDS));
                byte[] gotOnServer = serverReceived.poll(5, TimeUnit.SECONDS);
                assertArrayEquals(payload, gotOnServer, "server mismatch for size=" + size);

                byte[] gotOnClient = clientReceived.poll(5, TimeUnit.SECONDS);
                assertNotNull(gotOnClient);
                assertEquals(size + 1, gotOnClient.length);
                assertEquals((byte) 0xFF, gotOnClient[0]);
                assertArrayEquals(payload, Arrays.copyOfRange(gotOnClient, 1, gotOnClient.length));
            }

            assertNotNull(serverSessionRef.get());
            assertFalse(serverSessionRef.get().isAdaptiveUdpNegotiated());
            assertFalse(serverSessionRef.get().isUsingUdp());
        }
    }

    @Test
    @Timeout(20)
    void dailyAdaptiveMode_ActivatesSinglePortUdpAndStreamsEncryptedRawBytes() throws Exception {
        int port = freePort();
        TransportConfig serverConf = TransportConfig.dailyServer(port);
        serverConf.setAuthSecret("core-test-shared-secret");
        serverConf.getTcpServerConfig().setTransportFlags(TransportFlags.GFW.flags(TransportFlags.CIPHER_BOTH).flags());

        TransportConfig clientConf = TransportConfig.dailyClient(new InetSocketAddress("127.0.0.1", port));
        clientConf.setAuthSecret("core-test-shared-secret");
        clientConf.getTcpClientConfig().setTransportFlags(TransportFlags.GFW.flags(TransportFlags.CIPHER_BOTH).flags());

        BlockingQueue<byte[]> clientReceived = new LinkedBlockingQueue<byte[]>();

        try (TransportServer server = new TransportServer(serverConf);
             TransportClient client = new TransportClient(clientConf)) {
            server.setReceiveHandler((session, buf) -> {
                byte[] bytes = new byte[buf.readableBytes()];
                buf.readBytes(bytes);
                session.send(Unpooled.wrappedBuffer(bytes));
            });
            client.setReceiveHandler((session, buf) -> {
                byte[] bytes = new byte[buf.readableBytes()];
                buf.readBytes(bytes);
                clientReceived.offer(bytes);
            });

            server.start();
            assertTrue(server.udpPort() > 0, "Daily adaptive server must bind single UDP port");

            client.connect(new InetSocketAddress("127.0.0.1", port));
            TransportSession session = client.session();
            assertNotNull(session);
            assertTrue(session.isAdaptiveUdpNegotiated());

            // Exchange raw frames until AdaptiveTcpStream promotes to UDP fast path
            List<byte[]> sent = new ArrayList<byte[]>();
            for (int i = 0; i < 40; i++) {
                byte[] payload = randomBytes(400 + i * 37);
                sent.add(payload);
                client.send(Unpooled.wrappedBuffer(payload));
                byte[] echoed = clientReceived.poll(5, TimeUnit.SECONDS);
                assertArrayEquals(payload, echoed, "echo mismatch at frame " + i);
                if (i == 5) {
                    Thread.sleep(60);
                }
            }
            awaitTrue(() -> {
                client.send(Unpooled.wrappedBuffer(new byte[]{1, 2, 3, 4}));
                try {
                    clientReceived.poll(200, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ignored) {
                }
                return session.isUsingUdp();
            }, 5000, "Daily adaptive session should promote to UDP fast path");
        }
    }

    @Test
    @Timeout(20)
    void adaptiveClientToPureTcpServer_NegotiatesPureTcpCleanly() throws Exception {
        int port = freePort();
        TransportConfig serverConf = TransportConfig.pureTcpServer(port);
        TransportConfig clientConf = TransportConfig.dailyClient(new InetSocketAddress("127.0.0.1", port));

        BlockingQueue<String> received = new LinkedBlockingQueue<String>();
        try (TransportServer server = new TransportServer(serverConf);
             TransportClient client = new TransportClient(clientConf)) {
            server.setReceiveHandler((session, buf) -> session.send(buf.retainedSlice()));
            client.setReceiveHandler((session, buf) -> received.offer(buf.toString(StandardCharsets.UTF_8)));

            server.start();
            assertEquals(0, server.udpPort());
            client.connect(new InetSocketAddress("127.0.0.1", port));
            assertFalse(client.session().isAdaptiveUdpNegotiated());
            assertFalse(client.session().isUsingUdp());

            client.send(Unpooled.copiedBuffer("hello-pure-tcp", StandardCharsets.UTF_8));
            assertEquals("hello-pure-tcp", received.poll(5, TimeUnit.SECONDS));
        }
    }

    @Test
    @Timeout(20)
    void sessionAttrsSurviveDisconnectAndHeartbeatUpdatesRtt() throws Exception {
        int port = freePort();
        TransportConfig serverConf = TransportConfig.pureTcpServer(port);
        serverConf.getTcpServerConfig().setHeartbeatTimeout(4);
        TransportConfig clientConf = TransportConfig.pureTcpClient(new InetSocketAddress("127.0.0.1", port));
        clientConf.getTcpClientConfig().setHeartbeatTimeout(2);

        CountDownLatch disconnectedLatch = new CountDownLatch(1);
        AtomicReference<String> attrOnDisconnect = new AtomicReference<String>();
        AtomicReference<TransportSession> serverSessionRef = new AtomicReference<TransportSession>();

        try (TransportServer server = new TransportServer(serverConf);
             TransportClient client = new TransportClient(clientConf)) {
            server.onConnected.add((s, e) -> {
                e.getValue().attr("appName", "orders-svc");
                serverSessionRef.set(e.getValue());
            });
            server.onDisconnected.add((s, e) -> {
                attrOnDisconnect.set(e.getValue().attr("appName"));
                disconnectedLatch.countDown();
            });

            server.start();
            client.connect(new InetSocketAddress("127.0.0.1", port));

            awaitTrue(() -> client.session() != null && client.session().lastHeartbeatMillis() > 0L
                            && client.session().heartbeatRttMillis() >= 0L,
                    5000, "Client heartbeat ping/pong should populate RTT");
            assertNotNull(serverSessionRef.get());
            assertTrue(serverSessionRef.get().lastHeartbeatMillis() > 0L);

            client.close();
            assertTrue(disconnectedLatch.await(5, TimeUnit.SECONDS));
            assertEquals("orders-svc", attrOnDisconnect.get());
        }
    }

    @Test
    @Timeout(20)
    void sendOnDisconnectedClient_ReleasesByteBufAndThrows() {
        TransportClient client = new TransportClient(TransportConfig.pureTcpClient(new InetSocketAddress("127.0.0.1", 1)));
        ByteBuf buf = Unpooled.buffer(32).writeLong(42L);
        try {
            assertThrows(ClientDisconnectedException.class, () -> client.send(buf));
            assertEquals(0, buf.refCnt(), "send() must release ByteBuf even when disconnected");
        } finally {
            client.close();
        }
    }

    @Test
    @Timeout(20)
    void datagramTransport_RawAndProtectedFragmentedFecExchange() throws Exception {
        DatagramTransportConfig cfgA = new DatagramTransportConfig(0);
        DatagramTransportConfig cfgB = new DatagramTransportConfig(0);
        BlockingQueue<byte[]> rxA = new LinkedBlockingQueue<byte[]>();
        BlockingQueue<byte[]> rxB = new LinkedBlockingQueue<byte[]>();

        try (DatagramTransport a = DatagramTransport.bind(cfgA);
             DatagramTransport b = DatagramTransport.bind(cfgB)) {
            SocketConfig boundConfig = a.channel().attr(SocketConfig.ATTR_CONF).get();
            assertEquals(1, boundConfig.getReusePortBindCount(), "DatagramTransport must default to single bind");

            a.setReceiveHandler((sender, buf) -> {
                byte[] data = new byte[buf.readableBytes()];
                buf.readBytes(data);
                rxA.offer(data);
            });
            b.setReceiveHandler((sender, buf) -> {
                byte[] data = new byte[buf.readableBytes()];
                buf.readBytes(data);
                rxB.offer(data);
            });

            InetSocketAddress addrA = new InetSocketAddress("127.0.0.1", a.localEndpoint().getPort());
            InetSocketAddress addrB = new InetSocketAddress("127.0.0.1", b.localEndpoint().getPort());

            // 1. Plain raw UDP ByteBuf exchange
            byte[] small = "raw-datagram-ping".getBytes(StandardCharsets.UTF_8);
            assertTrue(a.send(addrB, Unpooled.wrappedBuffer(small)).await(5, TimeUnit.SECONDS));
            assertArrayEquals(small, rxB.poll(5, TimeUnit.SECONDS));

            // 2. Unresolved recipient releases caller ByteBuf cleanly
            ByteBuf rejected = Unpooled.buffer(16).writeInt(99);
            assertFalse(a.send(InetSocketAddress.createUnresolved("unresolved.invalid", 9999), rejected)
                    .await(5, TimeUnit.SECONDS) && false);
            assertEquals(0, rejected.refCnt(), "Rejected send must release ByteBuf");

            // 3. Attach RssDatagramHandler (FEC + bounded authenticated fragmentation) directly onto raw DatagramTransport
            String token = UUID.randomUUID().toString();
            UUID salt = UUID.randomUUID();
            byte[] authSecret = randomBytes(32);
            int features = RssDatagramHandler.FEC;
            b.installDatagramHandler(RssDatagramHandler.server(token, addr -> false, features, salt, authSecret, 1200, true));
            a.installDatagramHandler(RssDatagramHandler.client(token, addrB, addrB, 1200, features, salt, authSecret, true));

            byte[] largeDatagram = randomBytes(4096);
            io.netty.channel.ChannelFuture sendFuture = a.send(addrB, Unpooled.wrappedBuffer(largeDatagram));
            assertTrue(sendFuture.await(5, TimeUnit.SECONDS));
            assertTrue(sendFuture.isSuccess(), () -> "large datagram send failed: " + sendFuture.cause());
            assertArrayEquals(largeDatagram, rxB.poll(5, TimeUnit.SECONDS));

            byte[] replyDatagram = randomBytes(3200);
            io.netty.channel.ChannelFuture replyFuture = b.send(addrA, Unpooled.wrappedBuffer(replyDatagram));
            assertTrue(replyFuture.await(5, TimeUnit.SECONDS));
            assertTrue(replyFuture.isSuccess(), () -> "reply datagram send failed: " + replyFuture.cause());
            assertArrayEquals(replyDatagram, rxA.poll(5, TimeUnit.SECONDS));
        }
    }

    @Test
    @Timeout(20)
    void authSecretMismatchOrMissing_RejectedBeforeSessionOrReceiveHandler() throws Exception {
        int port = freePort();
        TransportConfig serverConf = TransportConfig.pureTcpServer(port);
        serverConf.setAuthSecret("strict-shared-secret");

        java.util.concurrent.atomic.AtomicInteger connectedCount = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger receivedCount = new java.util.concurrent.atomic.AtomicInteger();
        try (TransportServer server = new TransportServer(serverConf)) {
            server.onConnected.add((s, e) -> connectedCount.incrementAndGet());
            server.setReceiveHandler((session, buf) -> receivedCount.incrementAndGet());
            server.start();

            // 1. Wrong client auth secret is rejected before session or receiveHandler
            TransportConfig wrongConf = TransportConfig.pureTcpClient(new InetSocketAddress("127.0.0.1", port));
            wrongConf.setAuthSecret("wrong-secret");
            wrongConf.getTcpClientConfig().setConnectTimeoutMillis(1500);
            try (TransportClient wrongClient = new TransportClient(wrongConf)) {
                assertThrows(Exception.class, () -> wrongClient.connect(new InetSocketAddress("127.0.0.1", port)));
            }

            // 2. Missing client auth secret is rejected before session or receiveHandler
            TransportConfig noAuthConf = TransportConfig.pureTcpClient(new InetSocketAddress("127.0.0.1", port));
            noAuthConf.getTcpClientConfig().setConnectTimeoutMillis(1500);
            try (TransportClient noAuthClient = new TransportClient(noAuthConf)) {
                assertThrows(Exception.class, () -> noAuthClient.connect(new InetSocketAddress("127.0.0.1", port)));
            }

            Thread.sleep(150);
            assertEquals(0, connectedCount.get(), "Unauthenticated peer must never trigger onConnected");
            assertEquals(0, receivedCount.get(), "Unauthenticated peer must never reach receiveHandler");

            // 3. Matching secret succeeds
            TransportConfig okConf = TransportConfig.pureTcpClient(new InetSocketAddress("127.0.0.1", port));
            okConf.setAuthSecret("strict-shared-secret");
            try (TransportClient okClient = new TransportClient(okConf)) {
                okClient.connect(new InetSocketAddress("127.0.0.1", port));
                okClient.send(Unpooled.copiedBuffer("ok", StandardCharsets.UTF_8));
                awaitTrue(() -> receivedCount.get() == 1, 3000, "Authenticated client frame must be delivered");
            }
        }
    }

    private static byte[] randomBytes(int len) {
        byte[] out = new byte[len];
        ThreadLocalRandom.current().nextBytes(out);
        return out;
    }

    private static int freePort() throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        }
    }

    private static void awaitTrue(BooleanSupplier cond, long timeoutMillis, String msg) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return;
            }
            Thread.sleep(30);
        }
        assertTrue(cond.getAsBoolean(), msg);
    }
}
