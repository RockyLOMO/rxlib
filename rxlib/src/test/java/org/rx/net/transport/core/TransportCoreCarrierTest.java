package org.rx.net.transport.core;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import org.junit.jupiter.api.Test;
import org.rx.net.EgressCodec;
import org.rx.net.EgressConfig;
import org.rx.net.Sockets;
import org.rx.net.rss.tcp.AdaptiveTcpHandler;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class TransportCoreCarrierTest {
    private static final byte[] PSK = new byte[32];

    static {
        for (int i = 0; i < PSK.length; i++) {
            PSK[i] = (byte) (i * 7 + 11);
        }
    }

    @Test
    void aeadAndBusinessZlibOverAutoWorksForAllPhysicalEgressModes() {
        TransportCoreCarrier.NonceReplayCache replayCache = new TransportCoreCarrier.NonceReplayCache();
        for (EgressCodec mode : new EgressCodec[]{EgressCodec.NONE, EgressCodec.ZLIB_STORED, EgressCodec.HTTP}) {
            EmbeddedChannel client = new EmbeddedChannel();
            EmbeddedChannel server = new EmbeddedChannel();
            try {
                EgressCodec.installClient(client.pipeline(), mode);
                EgressCodec.installServer(server.pipeline(), EgressConfig.serverAutoAll(), EgressCodec.InnerProtocol.TRANSPORT_CORE);

                TransportCoreCarrier.ClientOptions cOpts = new TransportCoreCarrier.ClientOptions(
                        "u1", PSK, true, true, 3, false, 0, true, null, 0, null, 5000, 1 << 22);
                TransportCoreCarrier.ServerOptions sOpts = new TransportCoreCarrier.ServerOptions(
                        PSK, peer -> null, null, true, 1300, 3, replayCache, true, 5000, 1 << 22, false, false);
                client.pipeline().addLast(TransportCoreCarrier.HANDLER_NAME, TransportCoreCarrier.clientHandler(cOpts));
                server.pipeline().addLast(TransportCoreCarrier.HANDLER_NAME, TransportCoreCarrier.serverHandler(sOpts));

                byte[] body = new byte[8192];
                Arrays.fill(body, (byte) 'K');
                ChannelPromise writePromise = client.newPromise();
                client.writeAndFlush(Unpooled.wrappedBuffer(body), writePromise);
                assertFalse(writePromise.isDone(), "Plaintext must wait for authenticated HelloAck");

                transfer(client, server, 7);
                assertTrue(TransportCoreCarrier.isAeadAuthenticated(server.pipeline()));
                assertEquals(mode, server.attr(EgressCodec.ATTR_SELECTED).get());

                transfer(server, client, 11);
                assertTrue(TransportCoreCarrier.isAeadAuthenticated(client.pipeline()));
                assertTrue(writePromise.isSuccess());
                assertTrue(Sockets.hasTcpCompressionHandlers(client));
                assertTrue(Sockets.hasTcpCompressionHandlers(server));

                int c2sWire = transfer(client, server, 29);
                assertTrue(c2sWire > 0 && c2sWire < body.length / 4,
                        "Business zlib inside AEAD must stay highly compressed for mode=" + mode + ", wire=" + c2sWire);
                assertArrayEquals(body, take(server, body.length));

                server.writeAndFlush(Unpooled.wrappedBuffer(body));
                transfer(server, client, 31);
                assertArrayEquals(body, take(client, body.length));
            } finally {
                client.finishAndReleaseAll();
                server.finishAndReleaseAll();
            }
        }
    }

    @Test
    void unencryptedPlainWithBusinessZlibHasZeroAmbiguityUnderServerAuto() {
        // P1 §3.3 proof: unencrypted RSS plain + business zlib 3 starts with "RXTC", NEVER raw 0x78,
        // so server AUTO unambiguously selects NONE, ZLIB_STORED, or HTTP without colliding with business zlib!
        TransportCoreCarrier.NonceReplayCache replayCache = new TransportCoreCarrier.NonceReplayCache();
        byte[] userSecret = "rss-user-pwd".getBytes(StandardCharsets.UTF_8);
        for (EgressCodec mode : new EgressCodec[]{EgressCodec.NONE, EgressCodec.ZLIB_STORED, EgressCodec.HTTP}) {
            EmbeddedChannel client = new EmbeddedChannel();
            EmbeddedChannel server = new EmbeddedChannel();
            try {
                EgressCodec.installClient(client.pipeline(), mode);
                EgressCodec.installServer(server.pipeline(), EgressConfig.serverAutoAll(), EgressCodec.InnerProtocol.TRANSPORT_CORE);

                TransportCoreCarrier.ClientOptions cOpts = new TransportCoreCarrier.ClientOptions(
                        "alice", userSecret, false, true, 3, false, 0, false, null, 0, null, 5000, 1 << 22);
                TransportCoreCarrier.ServerOptions sOpts = new TransportCoreCarrier.ServerOptions(
                        null, peer -> "alice".equals(peer) ? userSecret : null, null, true, 1300, 3,
                        replayCache, true, 5000, 1 << 22, false, false);
                client.pipeline().addLast(TransportCoreCarrier.HANDLER_NAME, TransportCoreCarrier.clientHandler(cOpts));
                server.pipeline().addLast(TransportCoreCarrier.HANDLER_NAME, TransportCoreCarrier.serverHandler(sOpts));

                byte[] body = new byte[12 * 1024];
                Arrays.fill(body, (byte) 'Z');
                client.writeAndFlush(Unpooled.wrappedBuffer(body));

                transfer(client, server, 5);
                assertEquals(mode, server.attr(EgressCodec.ATTR_SELECTED).get(),
                        "Server AUTO must unambiguously select " + mode + " even with unencrypted business zlib");
                assertFalse(TransportCoreCarrier.isAeadAuthenticated(server.pipeline()));

                transfer(server, client, 7);
                int c2sWire = transfer(client, server, 53);
                assertTrue(c2sWire > 0 && c2sWire < body.length / 4);
                assertTrue(Sockets.hasTcpCompressionHandlers(server));
                assertArrayEquals(body, take(server, body.length));

                server.writeAndFlush(Unpooled.wrappedBuffer(body));
                transfer(server, client, 41);
                assertArrayEquals(body, take(client, body.length));
            } finally {
                client.finishAndReleaseAll();
                server.finishAndReleaseAll();
            }
        }
    }

    @Test
    void fragmentedHandshakeQueuedWriteAndMultiRecordAeadBidirectional() {
        EmbeddedChannel client = new EmbeddedChannel();
        EmbeddedChannel server = new EmbeddedChannel();
        try {
            TransportCoreCarrier.ClientOptions cOpts = new TransportCoreCarrier.ClientOptions(
                    "u1", PSK, true, false, 0, false, 0, false, null, 0, null, 5000, 1 << 22);
            TransportCoreCarrier.ServerOptions sOpts = new TransportCoreCarrier.ServerOptions(
                    PSK, peer -> null, null, false, 1300, 3, new TransportCoreCarrier.NonceReplayCache(),
                    true, 5000, 1 << 22, false, false);
            client.pipeline().addLast(TransportCoreCarrier.HANDLER_NAME, TransportCoreCarrier.clientHandler(cOpts));
            server.pipeline().addLast(TransportCoreCarrier.HANDLER_NAME, TransportCoreCarrier.serverHandler(sOpts));

            byte[] body = new byte[35_000];
            for (int i = 0; i < body.length; i++) {
                body[i] = (byte) (i * 31 + 3);
            }
            ChannelPromise written = client.newPromise();
            client.writeAndFlush(Unpooled.wrappedBuffer(body), written);
            assertFalse(written.isDone());

            transfer(client, server, 2);
            assertTrue(TransportCoreCarrier.isAeadAuthenticated(server.pipeline()));
            transfer(server, client, 3);
            assertTrue(TransportCoreCarrier.isAeadAuthenticated(client.pipeline()));
            assertTrue(written.isSuccess());

            transfer(client, server, 913);
            assertArrayEquals(body, take(server, body.length));
            server.writeAndFlush(Unpooled.wrappedBuffer(body));
            transfer(server, client, 701);
            assertArrayEquals(body, take(client, body.length));
        } finally {
            client.finishAndReleaseAll();
            server.finishAndReleaseAll();
        }
    }

    @Test
    void replayedHelloAndDuplicateAeadRecordFailAuthentication() {
        TransportCoreCarrier.NonceReplayCache sharedCache = new TransportCoreCarrier.NonceReplayCache();
        TransportCoreCarrier.ClientOptions cOpts = new TransportCoreCarrier.ClientOptions(
                "u1", PSK, true, false, 0, false, 0, false, null, 0, null, 5000, 1 << 22);
        TransportCoreCarrier.ServerOptions sOptsShared = new TransportCoreCarrier.ServerOptions(
                PSK, peer -> null, null, false, 1300, 3, sharedCache, true, 5000, 1 << 22, false, false);
        TransportCoreCarrier.ServerOptions sOptsOther = new TransportCoreCarrier.ServerOptions(
                PSK, peer -> null, null, false, 1300, 3, new TransportCoreCarrier.NonceReplayCache(),
                true, 5000, 1 << 22, false, false);

        EmbeddedChannel client = new EmbeddedChannel(TransportCoreCarrier.clientHandler(cOpts));
        EmbeddedChannel server = new EmbeddedChannel(TransportCoreCarrier.serverHandler(sOptsShared));
        EmbeddedChannel replaySameServer = new EmbeddedChannel(TransportCoreCarrier.serverHandler(sOptsShared));
        EmbeddedChannel replayOtherServer = new EmbeddedChannel(TransportCoreCarrier.serverHandler(sOptsOther));
        try {
            ByteBuf hello = client.readOutbound();
            try {
                // First server accepts fresh Hello
                server.writeInbound(hello.retainedDuplicate());
                assertTrue(TransportCoreCarrier.isAeadAuthenticated(server.pipeline()));

                // Same server's NonceReplayCache rejects replayed Hello immediately before sending HelloAck
                assertThrows(DecoderException.class, () -> replaySameServer.writeInbound(hello.retainedDuplicate()));
                assertFalse(replaySameServer.isOpen());
                assertNull(replaySameServer.readOutbound());
                assertFalse(TransportCoreCarrier.isAeadAuthenticated(replaySameServer.pipeline()));

                // Even an independent server without shared cache derives independent keys via fresh serverNonce
                replayOtherServer.writeInbound(hello.retainedDuplicate());
                ByteBuf staleAck = replayOtherServer.readOutbound();
                ByteBuf freshAck = server.readOutbound();
                assertNotEquals(staleAck, freshAck);
                staleAck.release();

                client.writeInbound(freshAck);
                client.writeAndFlush(Unpooled.wrappedBuffer(new byte[]{5, 1, 0}));
                ByteBuf record = client.readOutbound();
                try {
                    assertThrows(DecoderException.class, () -> replayOtherServer.writeInbound(record.retainedDuplicate()));
                    assertFalse(replayOtherServer.isOpen());
                    assertNull(replayOtherServer.readInbound());

                    server.writeInbound(record.retainedDuplicate());
                    assertArrayEquals(new byte[]{5, 1, 0}, take(server, 3));

                    // Replaying same AEAD record on the same connection fails monotonic GCM nonce verification
                    assertThrows(DecoderException.class, () -> server.writeInbound(record.retainedDuplicate()));
                    assertFalse(server.isOpen());
                    assertNull(server.readInbound());
                } finally {
                    record.release();
                }
            } finally {
                hello.release();
            }
        } finally {
            client.finishAndReleaseAll();
            server.finishAndReleaseAll();
            replaySameServer.finishAndReleaseAll();
            replayOtherServer.finishAndReleaseAll();
        }
    }

    @Test
    void wrongKeyRejectsBeforeLeakingQueuedPlaintext() {
        byte[] wrongPsk = new byte[32];
        Arrays.fill(wrongPsk, (byte) 0x42);
        TransportCoreCarrier.ClientOptions cOpts = new TransportCoreCarrier.ClientOptions(
                "u1", PSK, true, false, 0, false, 0, false, null, 0, null, 5000, 1 << 22);
        TransportCoreCarrier.ServerOptions sOpts = new TransportCoreCarrier.ServerOptions(
                wrongPsk, peer -> null, null, false, 1300, 3, new TransportCoreCarrier.NonceReplayCache(),
                true, 5000, 1 << 22, false, false);

        EmbeddedChannel client = new EmbeddedChannel(TransportCoreCarrier.clientHandler(cOpts));
        EmbeddedChannel server = new EmbeddedChannel(TransportCoreCarrier.serverHandler(sOpts));
        try {
            ByteBuf secret = Unpooled.wrappedBuffer(new byte[]{5, 1, 0});
            ChannelPromise queued = client.newPromise();
            client.writeAndFlush(secret, queued);
            assertFalse(queued.isDone());

            assertThrows(DecoderException.class, () -> transfer(client, server, 32));
            assertFalse(server.isOpen());
            assertNull(server.readOutbound());
            assertFalse(TransportCoreCarrier.isAeadAuthenticated(server.pipeline()));

            // Client closes when peer disconnects; queued plaintext is released & never sent
            client.close();
            assertTrue(queued.isDone());
            assertFalse(queued.isSuccess());
            assertEquals(0, secret.refCnt());
            assertNull(client.readOutbound());
        } finally {
            client.finishAndReleaseAll();
            server.finishAndReleaseAll();
        }
    }

    @Test
    void clientRejectsDowngradedAeadAndInvalidAdaptiveAckBeforeOpeningUdp() {
        byte[] secret = "auth-secret".getBytes(StandardCharsets.UTF_8);

        // 1. AEAD downgrade attempt in HelloAck
        EmbeddedChannel aeadClient = new EmbeddedChannel(TransportCoreCarrier.clientHandler(
                new TransportCoreCarrier.ClientOptions("u", PSK, true, false, 0, false, 0, false, null, 0, null, 5000, 1 << 22)));
        try {
            ByteBuf helloBuf = aeadClient.readOutbound();
            TransportProtocol.Hello hello = TransportProtocol.tryDecodeHello(helloBuf);
            helloBuf.release();
            assertNotNull(hello);
            int downgradedFlags = TransportProtocol.FLAG_RAW_STREAM | TransportProtocol.FLAG_AUTH; // stripped FLAG_AEAD!
            ByteBuf forgedAck = TransportProtocol.encodeHelloAck(aeadClient.alloc(), downgradedFlags, EgressCodec.NONE,
                    0, 0, 0, 0, hello.sessionId, hello.clientNonce,
                    TransportProtocol.randomBytes(TransportProtocol.SERVER_NONCE_BYTES), "", PSK);
            assertThrows(DecoderException.class, () -> aeadClient.writeInbound(forgedAck));
            assertFalse(aeadClient.isOpen());
            assertFalse(TransportCoreCarrier.isAeadAuthenticated(aeadClient.pipeline()));
        } finally {
            aeadClient.finishAndReleaseAll();
        }

        // 2. Mismatched single port (udpHop=false, primary != alias), inflated MTU, and wrong profile rejected before opening UDP
        for (int badCase = 0; badCase < 3; badCase++) {
            EmbeddedChannel adaptiveClient = new EmbeddedChannel(TransportCoreCarrier.clientHandler(
                    new TransportCoreCarrier.ClientOptions("u", secret, false, false, 0, true, 1, false, null, 512, null, 5000, 1 << 22)));
            try {
                ByteBuf helloBuf = adaptiveClient.readOutbound();
                TransportProtocol.Hello hello = TransportProtocol.tryDecodeHello(helloBuf);
                helloBuf.release();
                assertNotNull(hello);
                int ackProfile = badCase == 2 ? 2 : 1; // wrong profile in case 2
                int ackMtu = badCase == 1 ? 513 : 512; // inflated MTU in case 1
                int ackPrimary = 18000;
                int ackAlias = badCase == 0 ? 18001 : 18000; // mismatched alias in case 0 (no-hop)
                int ackFlags = TransportProtocol.FLAG_RAW_STREAM | TransportProtocol.FLAG_AUTH
                        | TransportProtocol.FLAG_ADAPTIVE_UDP | TransportProtocol.encodeProfileFlag(ackProfile);
                ByteBuf badAck = TransportProtocol.encodeHelloAck(adaptiveClient.alloc(), ackFlags, EgressCodec.NONE,
                        0, ackMtu, ackPrimary, ackAlias, hello.sessionId, hello.clientNonce,
                        TransportProtocol.randomBytes(TransportProtocol.SERVER_NONCE_BYTES), "", secret);
                assertThrows(DecoderException.class, () -> adaptiveClient.writeInbound(badAck));
                assertFalse(adaptiveClient.isOpen());
                assertNull(adaptiveClient.pipeline().get(AdaptiveTcpHandler.class));
            } finally {
                adaptiveClient.finishAndReleaseAll();
            }
        }
    }

    @Test
    void coalescedLeftoverAfterHelloAckEntersAeadAndZlibCleanly() {
        EmbeddedChannel client = new EmbeddedChannel(TransportCoreCarrier.clientHandler(
                new TransportCoreCarrier.ClientOptions("u", PSK, true, true, 3, false, 0, false, null, 0, null, 5000, 1 << 22)));
        EmbeddedChannel server = new EmbeddedChannel(TransportCoreCarrier.serverHandler(
                new TransportCoreCarrier.ServerOptions(PSK, p -> null, null, false, 1300, 3,
                        new TransportCoreCarrier.NonceReplayCache(), true, 5000, 1 << 22, false, false)));
        try {
            transfer(client, server, 64);
            // Server writes application bytes IMMEDIATELY after HelloAck so both arrive coalesced in one TCP segment!
            byte[] serverBanner = "COALESCED-POST-ACK-PAYLOAD-0123456789".getBytes(StandardCharsets.UTF_8);
            server.writeAndFlush(Unpooled.wrappedBuffer(serverBanner));

            ByteBuf coalesced = Unpooled.buffer();
            try {
                ByteBuf part;
                while ((part = server.readOutbound()) != null) {
                    try {
                        coalesced.writeBytes(part);
                    } finally {
                        part.release();
                    }
                }
                assertTrue(client.writeInbound(coalesced.retain()));
                assertArrayEquals(serverBanner, take(client, serverBanner.length));
            } finally {
                coalesced.release();
            }
        } finally {
            client.finishAndReleaseAll();
            server.finishAndReleaseAll();
        }
    }

    @Test
    void strictServerRejectsRawSocksAndZlibWhileHybridBypassesRawSocks() {
        // Strict RSS server rejects raw 0x05, raw zlib 0x78 0x9c, TLS 0x16 0x03 0x01, and invalid bytes
        for (byte[] bad : new byte[][]{{5, 1, 0}, {0x78, (byte) 0x9c, 0x03, 0x00}, {0x16, 3, 1, 0}, {0, 0, 0, 0}}) {
            EmbeddedChannel strict = new EmbeddedChannel(TransportCoreCarrier.serverHandler(
                    new TransportCoreCarrier.ServerOptions(PSK, p -> null, null, false, 1300, 3,
                            new TransportCoreCarrier.NonceReplayCache(), true, 5000, 1 << 22, false, false)));
            try {
                assertThrows(DecoderException.class, () -> strict.writeInbound(Unpooled.wrappedBuffer(bad)));
                assertFalse(strict.isOpen());
            } finally {
                strict.finishAndReleaseAll();
            }
        }

        // Hybrid server (requireCoreHandshake=false) bypasses raw SOCKS5 0x05 cleanly
        EmbeddedChannel hybrid = new EmbeddedChannel(TransportCoreCarrier.serverHandler(
                new TransportCoreCarrier.ServerOptions(PSK, p -> null, null, false, 1300, 3,
                        new TransportCoreCarrier.NonceReplayCache(), false, 5000, 1 << 22, false, false)));
        try {
            byte[] greeting = new byte[]{5, 1, 0};
            assertTrue(hybrid.writeInbound(Unpooled.wrappedBuffer(greeting)));
            assertArrayEquals(greeting, take(hybrid, greeting.length));
            assertNull(hybrid.pipeline().get(TransportCoreCarrier.ServerHandler.class));
        } finally {
            hybrid.finishAndReleaseAll();
        }

        // Handshake timeout closes idle connection cleanly with zero leaks
        EmbeddedChannel idle = new EmbeddedChannel(TransportCoreCarrier.serverHandler(
                new TransportCoreCarrier.ServerOptions(PSK, p -> null, null, false, 1300, 3,
                        new TransportCoreCarrier.NonceReplayCache(), true, 150, 1 << 22, false, false)));
        try {
            assertTrue(idle.isOpen());
            idle.advanceTimeBy(250, TimeUnit.MILLISECONDS);
            idle.runScheduledPendingTasks();
            assertFalse(idle.isOpen());
        } finally {
            idle.finishAndReleaseAll();
        }
    }

    private static int transfer(EmbeddedChannel from, EmbeddedChannel to, int chunk) {
        int total = 0;
        ByteBuf wire;
        while ((wire = from.readOutbound()) != null) {
            try {
                total += wire.readableBytes();
                while (wire.isReadable()) {
                    to.writeInbound(wire.readRetainedSlice(Math.min(chunk, wire.readableBytes())));
                }
            } finally {
                wire.release();
            }
        }
        return total;
    }

    private static byte[] take(EmbeddedChannel ch, int size) {
        byte[] result = new byte[size];
        int pos = 0;
        ByteBuf value;
        while ((value = ch.readInbound()) != null) {
            try {
                int n = value.readableBytes();
                value.readBytes(result, pos, n);
                pos += n;
            } finally {
                value.release();
            }
        }
        assertEquals(size, pos);
        return result;
    }
}
