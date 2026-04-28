package org.rx.net.transport;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.rx.exception.InvalidException;
import org.rx.net.transport.protocol.AckSync;

import java.io.Serializable;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UdpTransportTest {
    @Test
    @Timeout(10)
    void fullSyncResendsUntilHandlerCompletes() throws Exception {
        try (UdpClient server = new UdpClient(0);
             UdpClient client = new UdpClient(0)) {
            InetSocketAddress serverEndpoint = server.getLocalEndpoint();
            server.setWaitAckTimeoutMillis(1200);
            client.setWaitAckTimeoutMillis(1200);
            client.setMaxResend(3);

            AtomicInteger attempts = new AtomicInteger();
            CountDownLatch handled = new CountDownLatch(1);
            server.onReceive.add((s, e) -> {
                if (attempts.incrementAndGet() == 1) {
                    throw new InvalidException("first attempt fail");
                }
                handled.countDown();
            });

            client.sendAsync(serverEndpoint, "retry-check", 1200, true);

            assertTrue(handled.await(3, TimeUnit.SECONDS), "服务端应在重试后处理成功");
            assertEquals(2, attempts.get(), "首个 FULL ACK 失败后应触发一次重发");
        }
    }

    @Test
    @Timeout(10)
    void requestTransfersSerializableObjectAcrossFragments() throws Exception {
        try (UdpClient server = new UdpClient(0);
             UdpClient client = new UdpClient(0)) {
            InetSocketAddress serverEndpoint = server.getLocalEndpoint();
            server.setWaitAckTimeoutMillis(1500);
            client.setWaitAckTimeoutMillis(1500);
            server.setMaxFragmentPayloadBytes(128);
            client.setMaxFragmentPayloadBytes(128);

            byte[] payload = new byte[4096];
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) (i % 251);
            }
            EchoRequest request = new EchoRequest("frag-rpc", payload);

            server.onReceive.add((s, e) -> {
                EchoRequest packet = e.getValue().packet();
                s.reply(e.getValue(), new EchoResponse(packet.name, Arrays.copyOf(packet.payload, packet.payload.length)));
            });

            EchoResponse response = client.request(serverEndpoint, request, EchoResponse.class, 3000);
            assertEquals(request.name, response.name);
            assertArrayEquals(request.payload, response.payload);
        }
    }

    @Test
    @Timeout(10)
    void customCodecCanBeConfigured() throws Exception {
        UdpClientConfig config = new UdpClientConfig();
        config.setCodec(new StringUtf8Codec());

        try (UdpClient server = new UdpClient(0, config);
             UdpClient client = new UdpClient(0, config)) {
            InetSocketAddress serverEndpoint = server.getLocalEndpoint();

            CountDownLatch received = new CountDownLatch(1);
            String[] value = new String[1];
            server.onReceive.add((s, e) -> {
                value[0] = (String) e.getValue().packet();
                received.countDown();
            });

            client.send(serverEndpoint, "codec-config");

            assertTrue(received.await(3, TimeUnit.SECONDS), "server should receive custom-codec message");
            assertEquals("codec-config", value[0]);
        }
    }

    @Test
    void oversizedPayloadReleasesEncodedBuffer() {
        TrackingCodec codec = new TrackingCodec(12);
        try (UdpClient client = new UdpClient(0, codec)) {
            client.setMaxFragmentPayloadBytes(4);
            client.setMaxFragmentCount(2);

            assertThrows(InvalidException.class, () -> client.beginSend(new InetSocketAddress("127.0.0.1", 9),
                    "oversized", 1000, false, client.nextMessageId()));
            assertEquals(0, codec.lastEncoded.refCnt());
        }
    }

    @Test
    void duplicateFragmentIsReleased() {
        InetSocketAddress sender = new InetSocketAddress("127.0.0.1", 40001);
        ByteBuf first = Unpooled.directBuffer(4).writeInt(1);
        try (UdpClient client = new UdpClient(0, new StringUtf8Codec())) {
            ByteBuf duplicate = Unpooled.directBuffer(4).writeInt(2);
            client.handleData(sender, 10, AckSync.NONE, 1000, 0, 2, first);
            client.handleData(sender, 10, AckSync.NONE, 1000, 0, 2, duplicate);

            assertEquals(1, client.pendingReceives.size());
            assertEquals(1, first.refCnt());
            assertEquals(0, duplicate.refCnt());
        }
        assertEquals(0, first.refCnt());
    }

    @Test
    void assemblyTimeoutReleasesFragments() throws Exception {
        InetSocketAddress sender = new InetSocketAddress("127.0.0.1", 40002);
        try (UdpClient client = new UdpClient(0, new StringUtf8Codec())) {
            ByteBuf fragment = Unpooled.directBuffer(4).writeInt(9);
            client.handleData(sender, 11, AckSync.NONE, 80, 0, 2, fragment);

            Thread.sleep(250L);
            assertTrue(client.pendingReceives.isEmpty());
            assertEquals(0, fragment.refCnt());
        }
    }

    @Test
    void decodeFailureReleasesMergedPayload() {
        InetSocketAddress sender = new InetSocketAddress("127.0.0.1", 40003);
        CountDownLatch errorLatch = new CountDownLatch(1);
        try (UdpClient client = new UdpClient(0, new DecodeFailCodec())) {
            client.onError.add((s, e) -> errorLatch.countDown());
            ByteBuf payload = Unpooled.directBuffer(4).writeInt(7);

            client.handleData(sender, 12, AckSync.SEMI, 1000, 0, 1, payload);

            assertEquals(0, payload.refCnt());
            assertTrue(client.pendingReceives.isEmpty());
            assertTrue(errorLatch.await(2, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw InvalidException.sneaky(e);
        }
    }

    static final class EchoRequest implements Serializable {
        private static final long serialVersionUID = -5005224312237928424L;
        final String name;
        final byte[] payload;

        EchoRequest(String name, byte[] payload) {
            this.name = name;
            this.payload = payload;
        }
    }

    static final class EchoResponse implements Serializable {
        private static final long serialVersionUID = 3123942842540140752L;
        final String name;
        final byte[] payload;

        EchoResponse(String name, byte[] payload) {
            this.name = name;
            this.payload = payload;
        }
    }

    static final class StringUtf8Codec implements UdpClientCodec {
        private static final long serialVersionUID = -3078851604590204124L;

        @Override
        public ByteBuf encode(ByteBufAllocator allocator, Object packet) {
            ByteBuf payload = allocator.buffer();
            ByteBufUtil.writeUtf8(payload, String.valueOf(packet));
            return payload;
        }

        @Override
        public Object decode(ByteBuf payload) {
            return payload.toString(io.netty.util.CharsetUtil.UTF_8);
        }
    }

    static final class TrackingCodec implements UdpClientCodec {
        private static final long serialVersionUID = 2352375136636480525L;
        final int size;
        volatile ByteBuf lastEncoded;

        TrackingCodec(int size) {
            this.size = size;
        }

        @Override
        public ByteBuf encode(ByteBufAllocator allocator, Object packet) {
            lastEncoded = PooledByteBufAllocator.DEFAULT.directBuffer(size).writeZero(size);
            return lastEncoded;
        }

        @Override
        public Object decode(ByteBuf payload) {
            return null;
        }
    }

    static final class DecodeFailCodec implements UdpClientCodec {
        private static final long serialVersionUID = 5255437427784805867L;

        @Override
        public ByteBuf encode(ByteBufAllocator allocator, Object packet) {
            return Unpooled.buffer(0);
        }

        @Override
        public Object decode(ByteBuf payload) {
            throw new InvalidException("decode fail");
        }
    }
}
