package org.rx.net.transport;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.rx.exception.InvalidException;
import org.rx.net.udp.UdpPeerAttributes;
import org.rx.net.udp.UdpResilienceAttributes;
import org.rx.net.udp.UdpResilienceConfig;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KcpClientTest {
    @Test
    @Timeout(10)
    void kcpClientSendsMessagesInOrder() throws Exception {
        KcpClientConfig config = stringConfig();
        try (KcpClient server = new KcpClient(0, config);
             KcpClient client = new KcpClient(0, config)) {
            int count = 20;
            CountDownLatch received = new CountDownLatch(count);
            List<String> values = Collections.synchronizedList(new ArrayList<>());
            server.onReceive.add((s, e) -> {
                values.add(e.getValue().packet());
                received.countDown();
            });

            for (int i = 0; i < count; i++) {
                client.send(server.getLocalEndpoint(), "ordered-" + i).syncUninterruptibly();
            }

            assertTrue(received.await(4, TimeUnit.SECONDS));
            for (int i = 0; i < count; i++) {
                assertEquals("ordered-" + i, values.get(i));
            }
        }
    }

    @Test
    @Timeout(10)
    void kcpClientRetransmitsLostSegmentWithoutReorderingMessages() throws Exception {
        KcpClientConfig config = stringConfig();
        config.setIntervalMillis(10);
        try (KcpClient server = new KcpClient(0, config);
             KcpClient client = new KcpClient(0, config)) {
            AtomicBoolean dropped = new AtomicBoolean();
            client.getChannel().pipeline().addLast(new DropFirstKcpPush(dropped));
            CountDownLatch received = new CountDownLatch(2);
            List<String> values = Collections.synchronizedList(new ArrayList<>());
            server.onReceive.add((s, e) -> {
                values.add(e.getValue().packet());
                received.countDown();
            });

            client.send(server.getLocalEndpoint(), "first").syncUninterruptibly();
            client.send(server.getLocalEndpoint(), "second").syncUninterruptibly();

            assertTrue(received.await(4, TimeUnit.SECONDS));
            assertTrue(dropped.get());
            assertEquals("first", values.get(0));
            assertEquals("second", values.get(1));
        }
    }

    @Test
    @Timeout(10)
    void kcpClientRequestResponse() throws Exception {
        try (KcpClient server = new KcpClient(0);
             KcpClient client = new KcpClient(0)) {
            server.onReceive.add((s, e) -> s.reply(e.getValue(), "pong"));

            String response = client.request(server.getLocalEndpoint(), "ping", String.class, 3000);

            assertEquals("pong", response);
        }
    }

    @Test
    @Timeout(10)
    void kcpClientAuthenticatedHandshakeDeliversMessage() throws Exception {
        try (KcpClient server = new KcpClient(0, authenticatedConfig());
             KcpClient client = new KcpClient(0, authenticatedConfig())) {
            CountDownLatch received = new CountDownLatch(1);
            server.onReceive.add((s, e) -> received.countDown());

            client.send(server.getLocalEndpoint(), "authenticated").syncUninterruptibly();

            assertTrue(received.await(4, TimeUnit.SECONDS));
            assertEquals(1, server.sessionCount());
        }
    }

    @Test
    @Timeout(10)
    void kcpClientRetriesAuthenticatedOpenWhenFirstHandshakePacketIsLost() throws Exception {
        try (KcpClient server = new KcpClient(0, authenticatedConfig());
             KcpClient client = new KcpClient(0, authenticatedConfig())) {
            AtomicBoolean dropped = new AtomicBoolean();
            client.getChannel().pipeline().addLast(new DropFirstAuthenticatedOpen(dropped));
            CountDownLatch received = new CountDownLatch(1);
            server.onReceive.add((s, e) -> received.countDown());

            client.send(server.getLocalEndpoint(), "retry-open").syncUninterruptibly();

            assertTrue(received.await(4, TimeUnit.SECONDS));
            assertTrue(dropped.get());
        }
    }

    @Test
    @Timeout(5)
    void kcpClientRejectsInvalidAuthenticationKey() throws Exception {
        KcpClientConfig clientConfig = authenticatedConfig();
        clientConfig.setAuthenticationKey("wrong-kcp-key".getBytes(CharsetUtil.UTF_8));
        try (KcpClient server = new KcpClient(0, authenticatedConfig());
             KcpClient client = new KcpClient(0, clientConfig)) {
            CountDownLatch received = new CountDownLatch(1);
            server.onReceive.add((s, e) -> received.countDown());

            client.send(server.getLocalEndpoint(), "reject").syncUninterruptibly();

            assertFalse(received.await(300, TimeUnit.MILLISECONDS));
            assertEquals(0, server.sessionCount());
        }
    }

    @Test
    @Timeout(10)
    void kcpClientMigratesAuthenticatedSessionAfterNatRebinding() throws Exception {
        try (KcpClient server = new KcpClient(0, authenticatedConfig());
             KcpClient client = new KcpClient(0, authenticatedConfig())) {
            CountDownLatch established = new CountDownLatch(1);
            CountDownLatch migratedData = new CountDownLatch(1);
            server.onReceive.add((s, e) -> {
                if ("establish".equals(e.getValue().packet())) {
                    established.countDown();
                } else if ("migrated".equals(e.getValue().packet())) {
                    migratedData.countDown();
                }
            });
            client.send(server.getLocalEndpoint(), "establish").syncUninterruptibly();
            assertTrue(established.await(4, TimeUnit.SECONDS));

            CaptureNextAuthenticatedData capture = new CaptureNextAuthenticatedData(true);
            client.getChannel().pipeline().addLast(capture);
            client.send(server.getLocalEndpoint(), "migrated").syncUninterruptibly();
            ByteBuf data = capture.captured.get(2, TimeUnit.SECONDS);
            InetSocketAddress rebound = new InetSocketAddress("127.0.0.1",
                    client.getLocalEndpoint().getPort() + 1000);
            InetSocketAddress migratedRemote = server.getChannel().eventLoop().submit(() -> {
                DatagramPacket injected = new DatagramPacket(data, server.getLocalEndpoint(), rebound);
                try {
                    server.handlePacket(injected);
                    return server.sessions.values().iterator().next().remoteAddress;
                } finally {
                    ReferenceCountUtil.release(injected);
                }
            }).get(2, TimeUnit.SECONDS);

            assertEquals(rebound, migratedRemote);
            assertTrue(migratedData.await(2, TimeUnit.SECONDS));
        }
    }

    @Test
    @Timeout(10)
    void kcpClientRejectsNatRebindingWhenDisabled() throws Exception {
        KcpClientConfig serverConfig = authenticatedConfig();
        serverConfig.setAllowNatRebinding(false);
        try (KcpClient server = new KcpClient(0, serverConfig);
             KcpClient client = new KcpClient(0, authenticatedConfig())) {
            CountDownLatch established = new CountDownLatch(1);
            server.onReceive.add((s, e) -> established.countDown());
            client.send(server.getLocalEndpoint(), "establish").syncUninterruptibly();
            assertTrue(established.await(4, TimeUnit.SECONDS));

            CaptureNextAuthenticatedData capture = new CaptureNextAuthenticatedData(true);
            client.getChannel().pipeline().addLast(capture);
            client.send(server.getLocalEndpoint(), "rebind-disabled").syncUninterruptibly();
            ByteBuf data = capture.captured.get(2, TimeUnit.SECONDS);
            InetSocketAddress original = client.getLocalEndpoint();
            InetSocketAddress rebound = new InetSocketAddress("127.0.0.1", original.getPort() + 1000);
            InetSocketAddress remote = server.getChannel().eventLoop().submit(() -> {
                DatagramPacket injected = new DatagramPacket(data, server.getLocalEndpoint(), rebound);
                try {
                    server.handlePacket(injected);
                    return server.sessions.values().iterator().next().remoteAddress;
                } finally {
                    ReferenceCountUtil.release(injected);
                }
            }).get(2, TimeUnit.SECONDS);

            assertEquals(original, remote);
        }
    }

    @Test
    @Timeout(10)
    void kcpClientRejectsReflectedAuthenticatedInitiatorPacket() throws Exception {
        try (KcpClient server = new KcpClient(0, authenticatedConfig());
             KcpClient client = new KcpClient(0, authenticatedConfig())) {
            CountDownLatch serverReceived = new CountDownLatch(1);
            CountDownLatch clientReceived = new CountDownLatch(1);
            server.onReceive.add((s, e) -> serverReceived.countDown());
            client.onReceive.add((s, e) -> clientReceived.countDown());
            CaptureNextAuthenticatedData capture = new CaptureNextAuthenticatedData(false);
            client.getChannel().pipeline().addLast(capture);

            client.send(server.getLocalEndpoint(), "reflect").syncUninterruptibly();
            ByteBuf data = capture.captured.get(2, TimeUnit.SECONDS);
            assertTrue(serverReceived.await(4, TimeUnit.SECONDS));
            client.getChannel().eventLoop().submit(() -> {
                DatagramPacket reflected = new DatagramPacket(data, client.getLocalEndpoint(),
                        server.getLocalEndpoint());
                try {
                    client.handlePacket(reflected);
                } finally {
                    ReferenceCountUtil.release(reflected);
                }
            }).get(2, TimeUnit.SECONDS);

            assertFalse(clientReceived.await(200, TimeUnit.MILLISECONDS));
        }
    }

    @Test
    @Timeout(10)
    void kcpClientRequestTimeoutClearsPendingRequest() throws Exception {
        KcpClientConfig config = new KcpClientConfig();
        config.setRequestTimeoutMillis(80);
        try (KcpClient client = new KcpClient(0, config)) {
            CompletableFuture<String> future = client.requestAsync(
                    new InetSocketAddress("127.0.0.1", 9), "timeout", String.class, 80);

            ExecutionException exception = assertThrows(ExecutionException.class,
                    () -> future.get(2, TimeUnit.SECONDS));
            assertTrue(exception.getCause() instanceof TimeoutException);
            assertTrue(client.pendingRequests.isEmpty());
        }
    }

    @Test
    @Timeout(10)
    void kcpClientDecodeFailurePublishesError() throws Exception {
        KcpClientConfig serverConfig = new KcpClientConfig();
        serverConfig.setCodec(new DecodeFailCodec());
        KcpClientConfig clientConfig = stringConfig();
        try (KcpClient server = new KcpClient(0, serverConfig);
             KcpClient client = new KcpClient(0, clientConfig)) {
            CountDownLatch errors = new CountDownLatch(1);
            server.onError.add((s, e) -> errors.countDown());

            client.send(server.getLocalEndpoint(), "bad-payload").syncUninterruptibly();

            assertTrue(errors.await(3, TimeUnit.SECONDS));
        }
    }

    @Test
    @Timeout(10)
    void kcpClientWithCompression() throws Exception {
        KcpClientConfig config = stringConfig();
        config.setUdpCompressEnabled(true);
        assertPipelineReceive(config, repeatedValue("compress"));
    }

    @Test
    @Timeout(10)
    void kcpClientWithRedundantDatagrams() throws Exception {
        KcpClientConfig config = stringConfig();
        config.setUdpRedundantMultiplier(2);
        assertPipelineReceive(config, "redundant");
    }

    @Test
    @Timeout(10)
    void kcpClientWithFecPipeline() throws Exception {
        KcpClientConfig config = stringConfig();
        config.setUdpResilience(peerScopedResilience());
        assertPipelineReceive(config, "fec");
    }

    @Test
    @Timeout(10)
    void kcpClientWithCombinedOptimizationPipelines() throws Exception {
        KcpClientConfig config = stringConfig();
        config.setUdpCompressEnabled(true);
        config.setUdpRedundantMultiplier(2);
        config.setUdpResilience(peerScopedResilience());
        assertPipelineReceive(config, repeatedValue("combined"));
    }

    @Test
    @Timeout(10)
    void kcpClientAuthenticatedWithCombinedOptimizationPipelines() throws Exception {
        KcpClientConfig config = authenticatedConfig();
        config.setUdpCompressEnabled(true);
        config.setUdpRedundantMultiplier(2);
        config.setUdpResilience(peerScopedResilience());
        assertPipelineReceive(config, repeatedValue("authenticated-combined"));
    }

    @Test
    @Timeout(10)
    void kcpClientRegistersEnabledPipelinePeerAutomatically() throws Exception {
        KcpClientConfig config = stringConfig();
        config.setUdpCompressEnabled(true);
        config.setUdpResilience(peerScopedResilience());
        try (KcpClient server = new KcpClient(0, config);
             KcpClient client = new KcpClient(0, config)) {
            CountDownLatch received = new CountDownLatch(1);
            server.onReceive.add((s, e) -> received.countDown());

            client.send(server.getLocalEndpoint(), "plain").syncUninterruptibly();

            assertTrue(received.await(4, TimeUnit.SECONDS));
            assertTrue(UdpPeerAttributes.shouldEncode(client.getChannel(), server.getLocalEndpoint()));
            assertTrue(UdpResilienceAttributes.shouldApply(client.getChannel(), server.getLocalEndpoint()));
            assertTrue(UdpPeerAttributes.shouldEncode(server.getChannel(), client.getLocalEndpoint()));
            assertTrue(UdpResilienceAttributes.shouldApply(server.getChannel(), client.getLocalEndpoint()));
        }
    }

    @Test
    @Timeout(5)
    void kcpClientBackpressureAndCloseReleaseQueuedPayloads() {
        TrackingCodec codec = new TrackingCodec(64);
        KcpClientConfig config = new KcpClientConfig();
        config.setCodec(codec);
        config.setMaxPendingBytesPerSession(128);
        KcpClient client = new KcpClient(0, config);
        try {
            InetSocketAddress unreachable = new InetSocketAddress("127.0.0.1", 9);
            assertTrue(client.send(unreachable, "first").syncUninterruptibly().isSuccess());
            ChannelFuture rejected = client.send(unreachable, "second").awaitUninterruptibly();

            assertFalse(rejected.isSuccess());
            assertTrue(rejected.cause() instanceof InvalidException);
            assertEquals(2, codec.encoded.size());
            assertEquals(0, codec.encoded.get(1).refCnt());
        } finally {
            client.close();
        }
        assertEquals(0, codec.encoded.get(0).refCnt());
    }

    @Test
    @Timeout(5)
    void kcpClientRejectsNewOutboundSessionAtSessionLimit() {
        KcpClientConfig config = stringConfig();
        config.setMaxSessions(1);
        try (KcpClient client = new KcpClient(0, config)) {
            assertTrue(client.send(new InetSocketAddress("127.0.0.1", 9), "first")
                    .syncUninterruptibly().isSuccess());
            ChannelFuture rejected = client.send(new InetSocketAddress("127.0.0.1", 10), "second")
                    .awaitUninterruptibly();

            assertFalse(rejected.isSuccess());
            assertTrue(rejected.cause() instanceof InvalidException);
            assertEquals(1, client.sessionCount());
        }
    }

    @Test
    @Timeout(5)
    void kcpClientRemovesAutoPeerWhenLastSessionIsRemoved() throws Exception {
        KcpClientConfig config = stringConfig();
        config.setUdpCompressEnabled(true);
        config.setUdpResilience(peerScopedResilience());
        InetSocketAddress remote = new InetSocketAddress("127.0.0.1", 9);
        try (KcpClient client = new KcpClient(0, config)) {
            client.send(remote, "first").syncUninterruptibly();
            KcpClient.KcpSession session = client.outboundSessions.get(UdpResilienceAttributes.normalize(remote));

            assertTrue(UdpPeerAttributes.shouldEncode(client.getChannel(), remote));
            assertTrue(UdpResilienceAttributes.shouldApply(client.getChannel(), remote));
            client.getChannel().eventLoop().submit(() -> client.removeSession(session.key))
                    .get(2, TimeUnit.SECONDS);

            assertFalse(UdpPeerAttributes.shouldEncode(client.getChannel(), remote));
            assertFalse(UdpResilienceAttributes.shouldApply(client.getChannel(), remote));
        }
    }

    @Test
    @Timeout(5)
    void kcpClientCloseFromEventLoopDoesNotBlock() throws Exception {
        KcpClient client = new KcpClient(0, stringConfig());
        Channel channel = client.getChannel();

        channel.eventLoop().submit(client::close).get(2, TimeUnit.SECONDS);
        channel.closeFuture().syncUninterruptibly();

        assertFalse(channel.isOpen());
    }

    void assertPipelineReceive(KcpClientConfig config, String expected) throws Exception {
        try (KcpClient server = new KcpClient(0, config);
             KcpClient client = new KcpClient(0, config)) {
            CountDownLatch received = new CountDownLatch(1);
            String[] value = new String[1];
            server.onReceive.add((s, e) -> {
                value[0] = e.getValue().packet();
                received.countDown();
            });

            client.send(server.getLocalEndpoint(), expected).syncUninterruptibly();

            assertTrue(received.await(4, TimeUnit.SECONDS));
            assertEquals(expected, value[0]);
        }
    }

    static UdpResilienceConfig peerScopedResilience() {
        UdpResilienceConfig config = UdpResilienceConfig.light();
        config.setResilienceAll(false);
        return config;
    }

    static KcpClientConfig stringConfig() {
        KcpClientConfig config = new KcpClientConfig();
        config.setCodec(new StringUtf8Codec());
        return config;
    }

    static KcpClientConfig authenticatedConfig() {
        KcpClientConfig config = stringConfig();
        config.setAuthenticationKey("kcp-auth-test-key".getBytes(CharsetUtil.UTF_8));
        return config;
    }

    static String repeatedValue(String prefix) {
        StringBuilder value = new StringBuilder(prefix);
        for (int i = 0; i < 256; i++) {
            value.append("-same-value");
        }
        return value.toString();
    }

    static final class DropFirstKcpPush extends ChannelOutboundHandlerAdapter {
        final AtomicBoolean dropped;

        DropFirstKcpPush(AtomicBoolean dropped) {
            this.dropped = dropped;
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            if (msg instanceof DatagramPacket) {
                ByteBuf payload = ((DatagramPacket) msg).content();
                int readerIndex = payload.readerIndex();
                if (payload.readableBytes() >= KcpClient.HEADER_SIZE + 5
                        && payload.getInt(readerIndex) == KcpClient.MAGIC
                        && payload.getByte(readerIndex + 5) == KcpClient.TYPE_KCP_DATA
                        && payload.getUnsignedByte(readerIndex + KcpClient.HEADER_SIZE + 4) == 81
                        && dropped.compareAndSet(false, true)) {
                    ReferenceCountUtil.release(msg);
                    promise.setSuccess();
                    return;
                }
            }
            super.write(ctx, msg, promise);
        }
    }

    static final class DropFirstAuthenticatedOpen extends ChannelOutboundHandlerAdapter {
        final AtomicBoolean dropped;

        DropFirstAuthenticatedOpen(AtomicBoolean dropped) {
            this.dropped = dropped;
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            if (msg instanceof DatagramPacket) {
                ByteBuf payload = ((DatagramPacket) msg).content();
                int readerIndex = payload.readerIndex();
                if (payload.readableBytes() >= KcpClient.AUTH_DATAGRAM_HEADER_SIZE
                        && payload.getInt(readerIndex) == KcpClient.MAGIC
                        && payload.getByte(readerIndex + 4) == KcpClient.AUTH_VERSION
                        && payload.getByte(readerIndex + 5) == KcpClient.TYPE_OPEN
                        && dropped.compareAndSet(false, true)) {
                    ReferenceCountUtil.release(msg);
                    promise.setSuccess();
                    return;
                }
            }
            super.write(ctx, msg, promise);
        }
    }

    static final class CaptureNextAuthenticatedData extends ChannelOutboundHandlerAdapter {
        final CompletableFuture<ByteBuf> captured = new CompletableFuture<>();
        final boolean drop;

        CaptureNextAuthenticatedData(boolean drop) {
            this.drop = drop;
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            if (msg instanceof DatagramPacket) {
                ByteBuf payload = ((DatagramPacket) msg).content();
                int readerIndex = payload.readerIndex();
                if (payload.readableBytes() > KcpClient.AUTH_DATAGRAM_HEADER_SIZE
                        && payload.getInt(readerIndex) == KcpClient.MAGIC
                        && payload.getByte(readerIndex + 4) == KcpClient.AUTH_VERSION
                        && payload.getByte(readerIndex + 5) == KcpClient.TYPE_KCP_DATA
                        && captured.complete(payload.retainedDuplicate())) {
                    if (drop) {
                        ReferenceCountUtil.release(msg);
                        promise.setSuccess();
                        return;
                    }
                }
            }
            super.write(ctx, msg, promise);
        }
    }

    static final class StringUtf8Codec implements UdpClientCodec {
        private static final long serialVersionUID = 1L;

        @Override
        public ByteBuf encode(ByteBufAllocator allocator, Object packet) {
            ByteBuf payload = allocator.buffer();
            ByteBufUtil.writeUtf8(payload, String.valueOf(packet));
            return payload;
        }

        @Override
        public Object decode(ByteBuf payload) {
            return payload.toString(CharsetUtil.UTF_8);
        }
    }

    static final class DecodeFailCodec implements UdpClientCodec {
        private static final long serialVersionUID = 1L;

        @Override
        public ByteBuf encode(ByteBufAllocator allocator, Object packet) {
            throw new InvalidException("encode should not execute");
        }

        @Override
        public Object decode(ByteBuf payload) {
            throw new InvalidException("decode fail");
        }
    }

    static final class TrackingCodec implements UdpClientCodec {
        private static final long serialVersionUID = 1L;
        final List<ByteBuf> encoded = new ArrayList<>();
        final int bytes;

        TrackingCodec(int bytes) {
            this.bytes = bytes;
        }

        @Override
        public ByteBuf encode(ByteBufAllocator allocator, Object packet) {
            ByteBuf payload = Unpooled.directBuffer(bytes).writeZero(bytes);
            encoded.add(payload);
            return payload;
        }

        @Override
        public Object decode(ByteBuf payload) {
            return null;
        }
    }
}
