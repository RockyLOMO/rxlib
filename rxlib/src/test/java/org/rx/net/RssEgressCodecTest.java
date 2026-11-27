package org.rx.net;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import org.junit.jupiter.api.Test;
import org.rx.net.socks.SocksConfig;
import org.rx.net.socks.TcpWarmPoolKey;

import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class RssEgressCodecTest {
    @Test
    void noneHasNoHandlersAndDifferentFormatsCannotShareWarmConnections() {
        EmbeddedChannel ch = new EmbeddedChannel();
        try {
            int count = ch.pipeline().names().size();
            RssEgressCodec.NONE.install(ch.pipeline());
            assertEquals(count, ch.pipeline().names().size());
            SocksConfig config = new SocksConfig();
            AuthenticEndpoint ep = new AuthenticEndpoint(new InetSocketAddress("127.0.0.1", 1080));
            TcpWarmPoolKey raw = TcpWarmPoolKey.from(ep, config, "test");
            config.setRssEgressCodec(RssEgressCodec.ZLIB_STORED);
            assertNotEquals(raw, TcpWarmPoolKey.from(ep, config, "test"));
        } finally {
            ch.finishAndReleaseAll();
        }
    }

    @Test
    void storedStreamTransfersWithoutCompressingAndFlushesOnClose() {
        EmbeddedChannel sender = new EmbeddedChannel(), receiver = new EmbeddedChannel();
        try {
            RssEgressCodec.ZLIB_STORED.install(sender.pipeline());
            RssEgressCodec.ZLIB_STORED.install(receiver.pipeline());
            transfer(sender, receiver, 1);
            transfer(receiver, sender, 1);
            assertNull(receiver.readInbound());
            byte[] payload = new byte[16384];
            Arrays.fill(payload, (byte) 17);
            sender.writeOutbound(Unpooled.wrappedBuffer(payload));
            assertTrue(transfer(sender, receiver, 179) > payload.length);
            assertArrayEquals(payload, take(receiver, payload.length));
            receiver.writeOutbound(Unpooled.wrappedBuffer(payload));
            assertTrue(transfer(receiver, sender, 731) > payload.length);
            assertArrayEquals(payload, take(sender, payload.length));
            sender.close().syncUninterruptibly();
            assertTrue(transfer(sender, receiver, 1) > 0);
            assertNull(receiver.readInbound());
        } finally {
            sender.finishAndReleaseAll();
            receiver.finishAndReleaseAll();
        }
    }

    @Test
    void rejectsWrongVersionAndRawSocks() {
        for (int invalid : new int[]{0x52535a02, 0x05010000}) {
            EmbeddedChannel receiver = new EmbeddedChannel();
            try {
                RssEgressCodec.ZLIB_STORED.install(receiver.pipeline());
                assertFalse(receiver.writeInbound(Unpooled.buffer(4, 4).writeInt(invalid)));
                assertFalse(receiver.isOpen());
                assertNull(receiver.readInbound());
            } finally {
                receiver.finishAndReleaseAll();
            }
        }
    }

    @Test
    void malformedZlibAfterMatchingPrefaceClosesWithoutDeliveringBusinessBytes() {
        EmbeddedChannel receiver = new EmbeddedChannel();
        try {
            RssEgressCodec.ZLIB_STORED.install(receiver.pipeline());
            ByteBuf hello = receiver.readOutbound();
            assertFalse(receiver.writeInbound(hello));
            assertThrows(DecoderException.class,
                    () -> receiver.writeInbound(Unpooled.wrappedBuffer(new byte[]{1, 2, 3, 4, 5})));
            assertFalse(receiver.isOpen());
            assertNull(receiver.readInbound());
        } finally {
            receiver.finishAndReleaseAll();
        }
    }

    @Test
    void outerFormatPreservesAesPeerProofAndInnerCompression() {
        SecretKeySpec key = new SecretKeySpec(new byte[32], "HmacSHA256");
        EmbeddedChannel client = new EmbeddedChannel(), server = new EmbeddedChannel();
        try {
            RssEgressCodec.ZLIB_STORED.install(client.pipeline());
            RssEgressCodec.ZLIB_STORED.install(server.pipeline());
            SocksConfig config = new SocksConfig();
            config.setTcpCompressionLevel(3);
            client.pipeline().addLast(new RssHopAes(true, key));
            client.pipeline().addLast(Sockets.newTcpCompressionEncoder(config,
                    io.netty.handler.codec.compression.ZlibWrapper.ZLIB));
            server.pipeline().addLast(new RssTcpCodecSelector(config, key));
            byte[] data = new byte[16384];
            Arrays.fill(data, (byte) 5);
            client.writeOutbound(Unpooled.wrappedBuffer(data));
            transfer(client, server, 3);
            assertFalse(RssHopAes.authenticated(server.pipeline()));
            transfer(server, client, 5);
            int bytes = transfer(client, server, 7);
            assertTrue(bytes > 0 && bytes < data.length / 4);
            assertTrue(RssHopAes.authenticated(server.pipeline()));
            assertArrayEquals(data, take(server, data.length));
        } finally {
            client.finishAndReleaseAll();
            server.finishAndReleaseAll();
        }
    }

    @Test
    void serverAutoAcceptsBothZlibStoredAndPlainNoneWithoutDeadlock() {
        assertThrows(IllegalArgumentException.class, () -> RssEgressCodec.parse("auto"));
        assertEquals(RssEgressCodec.AUTO, RssEgressCodec.parseServer("auto"));
        SecretKeySpec key = new SecretKeySpec(new byte[32], "HmacSHA256");
        SocksConfig config = new SocksConfig();
        config.setTcpCompressionLevel(3);

        // 1. ZLIB_STORED client -> AUTO server (with RssHopAes + inner zlib 3).
        EmbeddedChannel storedClient = new EmbeddedChannel(), autoServer1 = new EmbeddedChannel();
        try {
            RssEgressCodec.ZLIB_STORED.install(storedClient.pipeline());
            RssEgressCodec.AUTO.installServer(autoServer1.pipeline());
            storedClient.pipeline().addLast(new RssHopAes(true, key));
            storedClient.pipeline().addLast(Sockets.newTcpCompressionEncoder(config,
                    io.netty.handler.codec.compression.ZlibWrapper.ZLIB));
            autoServer1.pipeline().addLast(new RssTcpCodecSelector(config, key));

            byte[] data = new byte[8192];
            Arrays.fill(data, (byte) 9);
            storedClient.writeOutbound(Unpooled.wrappedBuffer(data));
            transfer(storedClient, autoServer1, 64);
            transfer(autoServer1, storedClient, 64);
            transfer(storedClient, autoServer1, 64);
            assertTrue(RssHopAes.authenticated(autoServer1.pipeline()));
            assertArrayEquals(data, take(autoServer1, data.length));
            assertNotNull(autoServer1.pipeline().context(RssEgressCodec.INNER_ANCHOR));
        } finally {
            storedClient.finishAndReleaseAll();
            autoServer1.finishAndReleaseAll();
        }

        // 2. NONE client (3-byte raw SOCKS5 greeting) -> AUTO server removes detector on byte 1!
        EmbeddedChannel autoServer2 = new EmbeddedChannel();
        try {
            RssEgressCodec.AUTO.installServer(autoServer2.pipeline());
            byte[] socksGreeting = new byte[]{0x05, 0x01, 0x00};
            assertTrue(autoServer2.writeInbound(Unpooled.wrappedBuffer(socksGreeting)));
            assertNull(autoServer2.pipeline().get("rssEgressAuto"));
            assertNull(autoServer2.pipeline().context(RssEgressCodec.INNER_ANCHOR));
            assertArrayEquals(socksGreeting, take(autoServer2, socksGreeting.length));
        } finally {
            autoServer2.finishAndReleaseAll();
        }

        // 3. Invalid RSZ version -> AUTO server closes cleanly.
        EmbeddedChannel autoServer3 = new EmbeddedChannel();
        try {
            RssEgressCodec.AUTO.installServer(autoServer3.pipeline());
            assertFalse(autoServer3.writeInbound(Unpooled.buffer(4, 4).writeInt(0x52535a02)));
            assertFalse(autoServer3.isOpen());
        } finally {
            autoServer3.finishAndReleaseAll();
        }
    }

    @Test
    void compressionRunsOnceAboveAdaptiveStreamAndEgressWrapsOnlyPhysicalTcp() {
        SecretKeySpec key = new SecretKeySpec(new byte[32], "HmacSHA256");
        SocksConfig config = new SocksConfig();
        config.setTcpCompressionLevel(3);
        java.util.concurrent.atomic.AtomicInteger compressCalls = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(1_000_000L);
        java.io.ByteArrayOutputStream encodedAtBoundary = new java.io.ByteArrayOutputStream();
        java.io.ByteArrayOutputStream deliveredToServerInner = new java.io.ByteArrayOutputStream();
        java.util.ArrayDeque<ByteBuf> c2sUdp = new java.util.ArrayDeque<>(), s2cUdp = new java.util.ArrayDeque<>();
        int[] udpFramesSent = {0};
        long[] tcpInnerBytes = {0}, tcpWireBytes = {0};

        EmbeddedChannel clientInner = new EmbeddedChannel(), serverInner = new EmbeddedChannel();
        EmbeddedChannel clientTcp = new EmbeddedChannel(), serverTcp = new EmbeddedChannel();
        org.rx.net.rss.tcp.AdaptiveTcpStream clientStream = null, serverStream = null;
        try {
            RssEgressCodec.ZLIB_STORED.install(clientTcp.pipeline());
            RssEgressCodec.ZLIB_STORED.install(serverTcp.pipeline());
            clientInner.pipeline().addLast(new RssHopAes(true, key));
            clientInner.pipeline().addLast(Sockets.newTcpCompressionEncoder(config,
                    io.netty.handler.codec.compression.ZlibWrapper.ZLIB));
            clientInner.pipeline().addLast(new io.netty.channel.ChannelOutboundHandlerAdapter() {
                @Override public void write(io.netty.channel.ChannelHandlerContext ctx, Object msg, io.netty.channel.ChannelPromise promise) throws Exception {
                    if (msg instanceof ByteBuf && ((ByteBuf) msg).isReadable()) compressCalls.incrementAndGet();
                    super.write(ctx, msg, promise);
                }
            });
            serverInner.pipeline().addLast(new RssTcpCodecSelector(config, key));

            clientStream = new org.rx.net.rss.tcp.AdaptiveTcpStream(
                    io.netty.buffer.UnpooledByteBufAllocator.DEFAULT,
                    frame -> {
                        tcpInnerBytes[0] += frame.readableBytes();
                        ByteBuf framed = Unpooled.buffer(2 + frame.readableBytes()).writeShort(frame.readableBytes()).writeBytes(frame);
                        frame.release();
                        clientTcp.writeOutbound(framed);
                    },
                    udpFrame -> {
                        udpFramesSent[0]++;
                        if (udpFrame.readableBytes() >= 4) {
                            assertNotEquals(0x52535a01, udpFrame.getInt(udpFrame.readerIndex()),
                                    "UDP fast path must never carry RssEgressCodec preface");
                        }
                        c2sUdp.add(udpFrame);
                    },
                    bytes -> clientInner.writeInbound(bytes),
                    clock::get,
                    org.rx.net.rss.tcp.AdaptiveTcpStream.WEAK);

            serverStream = new org.rx.net.rss.tcp.AdaptiveTcpStream(
                    io.netty.buffer.UnpooledByteBufAllocator.DEFAULT,
                    frame -> {
                        ByteBuf framed = Unpooled.buffer(2 + frame.readableBytes()).writeShort(frame.readableBytes()).writeBytes(frame);
                        frame.release();
                        serverTcp.writeOutbound(framed);
                    },
                    s2cUdp::add,
                    bytes -> {
                        byte[] copy = new byte[bytes.readableBytes()];
                        bytes.getBytes(bytes.readerIndex(), copy);
                        deliveredToServerInner.write(copy, 0, copy.length);
                        serverInner.writeInbound(bytes);
                    },
                    clock::get,
                    org.rx.net.rss.tcp.AdaptiveTcpStream.WEAK);

            ByteBuf c2sTcpRx = Unpooled.buffer(), s2cTcpRx = Unpooled.buffer();
            try {
                // Exchange ZLIB_STORED prefaces on physical TCP.
                tcpWireBytes[0] += transfer(clientTcp, serverTcp, 2);
                transfer(serverTcp, clientTcp, 2);

                // Activate AdaptiveTcpStream UDP path via PING/PONG.
                clientStream.tick();
                serverStream.tick();
                drainUdp(c2sUdp, serverStream);
                drainUdp(s2cUdp, clientStream);
                drainUdp(c2sUdp, serverStream);
                assertTrue(clientStream.usingUdp());
                assertTrue(serverStream.usingUdp());

                // Write 3 compressible chunks (24 KiB total) through business zlib 3 -> RssHopAes -> AdaptiveTcpStream.
                byte[] expected = new byte[24 * 1024];
                for (int i = 0; i < expected.length; i++) expected[i] = (byte) ((i / 64) & 0x7f);
                // Trigger AES handshake with chunk 0.
                clientInner.writeOutbound(Unpooled.wrappedBuffer(expected, 0, 8192));
                feedInnerOutbound(clientInner, clientStream, encodedAtBoundary);
                tcpWireBytes[0] += pumpPhysicalTcp(clientTcp, serverTcp, c2sTcpRx, serverStream);
                drainUdp(c2sUdp, serverStream);
                feedServerInnerOutbound(serverInner, serverStream);
                pumpPhysicalTcp(serverTcp, clientTcp, s2cTcpRx, clientStream);
                drainUdp(s2cUdp, clientStream);

                // After handshake completes, write chunks 1 & 2, drop 2 UDP frames and reorder 1 to force repair!
                clientInner.writeOutbound(Unpooled.wrappedBuffer(expected, 8192, 8192));
                clientInner.writeOutbound(Unpooled.wrappedBuffer(expected, 16384, 8192));
                feedInnerOutbound(clientInner, clientStream, encodedAtBoundary);
                assertEquals(3, compressCalls.get(), "business compressor must run once per application write");
                assertTrue(udpFramesSent[0] > 0, "encoded business stream must traverse adaptive UDP");

                // Drop first two UDP frames (if available) and reverse the next two to exercise repair + reorder.
                int dropped = 0;
                while (dropped < 2 && c2sUdp.size() > 2) {
                    c2sUdp.removeFirst().release();
                    dropped++;
                }
                if (c2sUdp.size() >= 2) {
                    ByteBuf a = c2sUdp.removeFirst(), b = c2sUdp.removeFirst();
                    c2sUdp.addFirst(a);
                    c2sUdp.addFirst(b);
                }
                drainUdp(c2sUdp, serverStream);
                // Advance clock to trigger SACK + TCP repair through RssEgressCodec.ZLIB_STORED, then drain on FIN.
                for (int step = 0; step < 6; step++) {
                    clock.addAndGet(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(60));
                    serverStream.tick();
                    clientStream.tick();
                    pumpPhysicalTcp(serverTcp, clientTcp, s2cTcpRx, clientStream);
                    tcpWireBytes[0] += pumpPhysicalTcp(clientTcp, serverTcp, c2sTcpRx, serverStream);
                    drainUdp(c2sUdp, serverStream);
                    drainUdp(s2cUdp, clientStream);
                }
                clientStream.drainToTcp();
                tcpWireBytes[0] += pumpPhysicalTcp(clientTcp, serverTcp, c2sTcpRx, serverStream);

                assertEquals(3, compressCalls.get(), "FEC/UDP/TCP repair must never re-invoke business compressor");
                assertTrue(RssHopAes.authenticated(serverInner.pipeline()));
                assertArrayEquals(encodedAtBoundary.toByteArray(), deliveredToServerInner.toByteArray(),
                        "AdaptiveTcpStream must deliver byte-identical post-business-codec stream");
                assertArrayEquals(expected, take(serverInner, expected.length));
                assertTrue(tcpWireBytes[0] > tcpInnerBytes[0],
                        "physical TCP lane must traverse ZLIB_STORED wire=" + tcpWireBytes[0] + " inner=" + tcpInnerBytes[0]);
            } finally {
                c2sTcpRx.release();
                s2cTcpRx.release();
            }
        } finally {
            while (!c2sUdp.isEmpty()) c2sUdp.remove().release();
            while (!s2cUdp.isEmpty()) s2cUdp.remove().release();
            if (clientStream != null) clientStream.close();
            if (serverStream != null) serverStream.close();
            clientInner.finishAndReleaseAll();
            serverInner.finishAndReleaseAll();
            clientTcp.finishAndReleaseAll();
            serverTcp.finishAndReleaseAll();
        }
    }

    private static void feedInnerOutbound(EmbeddedChannel inner, org.rx.net.rss.tcp.AdaptiveTcpStream stream,
                                          java.io.ByteArrayOutputStream record) {
        ByteBuf buf;
        while ((buf = inner.readOutbound()) != null) {
            try {
                byte[] copy = new byte[buf.readableBytes()];
                buf.getBytes(buf.readerIndex(), copy);
                record.write(copy, 0, copy.length);
                stream.write(buf);
            } finally {
                buf.release();
            }
        }
    }

    private static void feedServerInnerOutbound(EmbeddedChannel inner, org.rx.net.rss.tcp.AdaptiveTcpStream stream) {
        ByteBuf buf;
        while ((buf = inner.readOutbound()) != null) {
            try {
                stream.write(buf);
            } finally {
                buf.release();
            }
        }
    }

    private static void drainUdp(java.util.ArrayDeque<ByteBuf> queue, org.rx.net.rss.tcp.AdaptiveTcpStream dest) {
        while (!queue.isEmpty()) {
            ByteBuf pkt = queue.remove();
            try {
                dest.udp(pkt);
            } finally {
                pkt.release();
            }
        }
    }

    private static int pumpPhysicalTcp(EmbeddedChannel from, EmbeddedChannel to, ByteBuf accum,
                                       org.rx.net.rss.tcp.AdaptiveTcpStream dest) {
        int wire = transfer(from, to, 193);
        ByteBuf part;
        while ((part = to.readInbound()) != null) {
            try {
                accum.writeBytes(part);
            } finally {
                part.release();
            }
        }
        while (accum.readableBytes() >= 2) {
            int len = accum.getUnsignedShort(accum.readerIndex());
            if (accum.readableBytes() < 2 + len) break;
            accum.skipBytes(2);
            ByteBuf frame = accum.readRetainedSlice(len);
            try {
                dest.tcp(frame);
            } finally {
                frame.release();
            }
        }
        accum.discardReadBytes();
        return wire;
    }

    private static int transfer(EmbeddedChannel from, EmbeddedChannel to, int fragment) {
        int n = 0;
        ByteBuf wire;
        while ((wire = from.readOutbound()) != null) {
            try {
                n += wire.readableBytes();
                while (wire.isReadable()) {
                    to.writeInbound(wire.readRetainedSlice(Math.min(fragment, wire.readableBytes())));
                }
            } finally {
                wire.release();
            }
        }
        return n;
    }

    private static byte[] take(EmbeddedChannel ch, int size) {
        byte[] data = new byte[size];
        int i = 0;
        ByteBuf msg;
        while ((msg = ch.readInbound()) != null) {
            try {
                int count = msg.readableBytes();
                msg.readBytes(data, i, count);
                i += count;
            } finally {
                msg.release();
            }
        }
        assertEquals(size, i);
        return data;
    }
}
