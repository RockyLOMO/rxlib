package org.rx.net;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponseDecoder;
import org.junit.jupiter.api.Test;
import org.rx.net.EgressCodec.HttpBodyEncoding;
import org.rx.net.EgressCodec.InnerProtocol;
import org.rx.net.socks.SocksConfig;
import org.rx.net.socks.TcpWarmPoolKey;

import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.zip.Inflater;

import static org.junit.jupiter.api.Assertions.*;

class EgressCodecTest {

    @Test
    void noneInstallsZeroHandlersAndWarmPoolKeysSeparateCodecs() {
        EmbeddedChannel ch = new EmbeddedChannel();
        try {
            int count = ch.pipeline().names().size();
            EgressCodec.installClient(ch.pipeline(), EgressCodec.NONE);
            assertEquals(count, ch.pipeline().names().size());
            assertEquals(EgressCodec.NONE, ch.attr(EgressCodec.ATTR_SELECTED).get());

            SocksConfig config = new SocksConfig();
            AuthenticEndpoint ep = new AuthenticEndpoint(new InetSocketAddress("127.0.0.1", 1080));
            TcpWarmPoolKey raw = TcpWarmPoolKey.from(ep, config, "test");
            config.setEgressCodec(EgressCodec.ZLIB_STORED);
            TcpWarmPoolKey stored = TcpWarmPoolKey.from(ep, config, "test");
            config.setEgressCodec(EgressCodec.HTTP);
            TcpWarmPoolKey http = TcpWarmPoolKey.from(ep, config, "test");
            assertNotEquals(raw, stored);
            assertNotEquals(raw, http);
            assertNotEquals(stored, http);
        } finally {
            ch.finishAndReleaseAll();
        }
    }

    @Test
    void configValidationRejectsClientAutoAndEmptyServerAllowedList() {
        assertThrows(IllegalArgumentException.class, () -> EgressCodec.parse("auto"));
        assertThrows(IllegalArgumentException.class, () -> EgressConfig.client(EgressCodec.AUTO));
        assertEquals(EgressCodec.AUTO, EgressCodec.parseServer("auto"));
        assertEquals(EgressCodec.HTTP, EgressCodec.parse("http"));

        EgressConfig emptyAuto = new EgressConfig();
        emptyAuto.setTcpCodec(EgressCodec.AUTO);
        emptyAuto.setAllowedCodecs(Collections.<EgressCodec>emptySet());
        assertThrows(IllegalArgumentException.class, emptyAuto::validateServer);

        EgressConfig nestedAuto = new EgressConfig();
        nestedAuto.setTcpCodec(EgressCodec.AUTO);
        nestedAuto.setAllowedCodecs(Collections.singleton(EgressCodec.AUTO));
        assertThrows(IllegalArgumentException.class, nestedAuto::validateServer);
    }

    @Test
    void pureRfc1950ZlibStoredHasNoRszPrefaceAndStandardInflaterDecodesIt() throws Exception {
        EmbeddedChannel sender = new EmbeddedChannel(), receiver = new EmbeddedChannel();
        try {
            EgressCodec.installClient(sender.pipeline(), EgressCodec.ZLIB_STORED);
            EgressCodec.installServer(receiver.pipeline(), EgressCodec.ZLIB_STORED, InnerProtocol.TRANSPORT_CORE);

            byte[] payload = new byte[16384];
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) (i * 31 + 7);
            }
            sender.writeOutbound(Unpooled.wrappedBuffer(payload));
            byte[] wire = readAllOutbound(sender);
            // Must be pure RFC 1950 zlib (NO 4B RSZ preface!)
            assertTrue(wire.length > payload.length);
            assertTrue(EgressCodec.isValidRfc1950Header(wire[0] & 0xff, wire[1] & 0xff),
                    "ZLIB_STORED wire must start with valid RFC 1950 zlib header, not RSZ preface");
            assertNotEquals('R', wire[0]);

            // Standard JDK Inflater directly decodes the stream
            Inflater inflater = new Inflater(false);
            ByteArrayOutputStream inflated = new ByteArrayOutputStream();
            try {
                inflater.setInput(wire);
                byte[] tmp = new byte[4096];
                while (inflated.size() < payload.length) {
                    int n = inflater.inflate(tmp);
                    if (n <= 0) {
                        break;
                    }
                    inflated.write(tmp, 0, n);
                }
            } finally {
                inflater.end();
            }
            assertArrayEquals(payload, inflated.toByteArray());

            // Feed fragmented bytes to receiver
            for (int off = 0; off < wire.length; off += 137) {
                int len = Math.min(137, wire.length - off);
                receiver.writeInbound(Unpooled.wrappedBuffer(wire, off, len));
            }
            assertArrayEquals(payload, takeInbound(receiver, payload.length));

            // Bidirectional response + close trailer
            receiver.writeOutbound(Unpooled.wrappedBuffer(payload));
            assertTrue(transfer(receiver, sender, 251) > payload.length);
            assertArrayEquals(payload, takeInbound(sender, payload.length));
            sender.close().syncUninterruptibly();
            assertTrue(transfer(sender, receiver, 1) > 0);
            assertNull(receiver.readInbound());
        } finally {
            sender.finishAndReleaseAll();
            receiver.finishAndReleaseAll();
        }
    }

    @Test
    void httpSchemeABinaryBodyIsValidHttp11FullDuplexAndRejectsMalformedOrOversized() {
        EmbeddedChannel client = new EmbeddedChannel(), server = new EmbeddedChannel();
        EmbeddedChannel stdReqParser = new EmbeddedChannel(new HttpRequestDecoder(), new HttpObjectAggregator(1024 * 1024));
        EmbeddedChannel stdRespParser = new EmbeddedChannel(new HttpResponseDecoder(), new HttpObjectAggregator(1024 * 1024));
        try {
            EgressCodec.installClient(client.pipeline(), EgressCodec.HTTP);
            EgressCodec.installServer(server.pipeline(), EgressCodec.HTTP, InnerProtocol.TRANSPORT_CORE);

            byte[] reqBin = new byte[4096];
            for (int i = 0; i < reqBin.length; i++) {
                reqBin[i] = (byte) (i & 0xff); // arbitrary binary bytes (non-JSON, includes NUL/0xFF)
            }
            // Empty write passes through without emitting bogus HTTP header
            client.writeOutbound(Unpooled.EMPTY_BUFFER);
            ByteBuf emptyOut = client.readOutbound();
            if (emptyOut != null) {
                assertEquals(0, emptyOut.readableBytes());
                emptyOut.release();
            }

            client.writeOutbound(Unpooled.wrappedBuffer(reqBin));
            byte[] c2sWire = readAllOutbound(client);
            String c2sPrefix = new String(c2sWire, 0, Math.min(200, c2sWire.length), StandardCharsets.US_ASCII);
            assertTrue(c2sPrefix.startsWith("POST /v1/stream HTTP/1.1\r\n"));
            assertTrue(c2sPrefix.contains("Content-Type: application/json\r\n"));
            assertTrue(c2sPrefix.contains("Content-Length: 4096\r\n"));

            // Verify Netty's own standard HttpRequestDecoder parses it cleanly!
            assertTrue(stdReqParser.writeInbound(Unpooled.wrappedBuffer(c2sWire)));
            FullHttpRequest parsedReq = stdReqParser.readInbound();
            assertNotNull(parsedReq);
            try {
                assertEquals("application/json", parsedReq.headers().get("Content-Type"));
                byte[] bodyBytes = new byte[parsedReq.content().readableBytes()];
                parsedReq.content().readBytes(bodyBytes);
                assertArrayEquals(reqBin, bodyBytes);
            } finally {
                parsedReq.release();
            }

            // Deliver fragmented c2s wire to server
            for (int off = 0; off < c2sWire.length; off += 19) {
                server.writeInbound(Unpooled.wrappedBuffer(c2sWire, off, Math.min(19, c2sWire.length - off)));
            }
            assertArrayEquals(reqBin, takeInbound(server, reqBin.length));

            // Full-duplex: server sends TWO independent HTTP 200 responses back-to-back without waiting for another POST!
            byte[] resp1 = "first-server-push-binary".getBytes(StandardCharsets.UTF_8);
            byte[] resp2 = new byte[]{0x00, 0x01, (byte) 0xFE, (byte) 0xFF, 0x42};
            server.writeOutbound(Unpooled.wrappedBuffer(resp1));
            server.writeOutbound(Unpooled.wrappedBuffer(resp2));
            byte[] s2cWire = readAllOutbound(server);

            assertTrue(stdRespParser.writeInbound(Unpooled.wrappedBuffer(s2cWire)));
            FullHttpResponse r1 = stdRespParser.readInbound();
            FullHttpResponse r2 = stdRespParser.readInbound();
            assertNotNull(r1);
            assertNotNull(r2);
            try {
                assertEquals(200, r1.status().code());
                assertEquals("application/json", r1.headers().get("Content-Type"));
                assertEquals(resp1.length, r1.content().readableBytes());
                assertEquals(resp2.length, r2.content().readableBytes());
            } finally {
                r1.release();
                r2.release();
            }

            // Deliver coalesced s2c responses to client in 11-byte fragments
            for (int off = 0; off < s2cWire.length; off += 11) {
                client.writeInbound(Unpooled.wrappedBuffer(s2cWire, off, Math.min(11, s2cWire.length - off)));
            }
            byte[] combined = new byte[resp1.length + resp2.length];
            System.arraycopy(resp1, 0, combined, 0, resp1.length);
            System.arraycopy(resp2, 0, combined, resp1.length, resp2.length);
            assertArrayEquals(combined, takeInbound(client, combined.length));
        } finally {
            client.finishAndReleaseAll();
            server.finishAndReleaseAll();
            stdReqParser.finishAndReleaseAll();
            stdRespParser.finishAndReleaseAll();
        }

        // Negative security/bounds tests on HTTP decoder:
        // 1. Oversized header without CRLFCRLF
        EgressConfig tight = EgressConfig.server(EgressCodec.HTTP);
        tight.setMaxHeaderBytes(256);
        tight.setMaxBodyBytes(1024);
        EmbeddedChannel badHeader = new EmbeddedChannel();
        try {
            EgressCodec.installServer(badHeader.pipeline(), tight, InnerProtocol.TRANSPORT_CORE);
            byte[] flood = new byte[512];
            Arrays.fill(flood, (byte) 'A');
            assertThrows(TooLongFrameException.class, () -> badHeader.writeInbound(Unpooled.wrappedBuffer(flood)));
            assertFalse(badHeader.isOpen());
        } finally {
            badHeader.finishAndReleaseAll();
        }

        // 2. Oversized Content-Length
        EmbeddedChannel badBody = new EmbeddedChannel();
        try {
            EgressCodec.installServer(badBody.pipeline(), tight, InnerProtocol.TRANSPORT_CORE);
            String huge = "POST /v1/stream HTTP/1.1\r\nHost: a\r\nContent-Length: 2048\r\n\r\n";
            assertThrows(TooLongFrameException.class, () -> badBody.writeInbound(Unpooled.copiedBuffer(huge, StandardCharsets.US_ASCII)));
            assertFalse(badBody.isOpen());
        } finally {
            badBody.finishAndReleaseAll();
        }

        // 3. Transfer-Encoding smuggling attempt & duplicate Content-Length & wrong method
        for (String badReq : new String[]{
                "POST /v1/stream HTTP/1.1\r\nHost: a\r\nTransfer-Encoding: chunked\r\nContent-Length: 4\r\n\r\nabcd",
                "POST /v1/stream HTTP/1.1\r\nHost: a\r\nContent-Length: 4\r\nContent-Length: 4\r\n\r\nabcd",
                "GET /v1/stream HTTP/1.1\r\nHost: a\r\nContent-Length: 4\r\n\r\nabcd"
        }) {
            EmbeddedChannel bad = new EmbeddedChannel();
            try {
                EgressCodec.installServer(bad.pipeline(), tight, InnerProtocol.TRANSPORT_CORE);
                assertThrows(CorruptedFrameException.class, () -> bad.writeInbound(Unpooled.copiedBuffer(badReq, StandardCharsets.US_ASCII)));
                assertFalse(bad.isOpen());
            } finally {
                bad.finishAndReleaseAll();
            }
        }
    }

    @Test
    void optionalHttpBodyZlibStoredRoundTripsIndependently() {
        EgressConfig cCfg = EgressConfig.client(EgressCodec.HTTP);
        cCfg.setHttpBodyEncoding(HttpBodyEncoding.ZLIB_STORED);
        EgressConfig sCfg = EgressConfig.server(EgressCodec.HTTP);
        sCfg.setHttpBodyEncoding(HttpBodyEncoding.ZLIB_STORED);

        EmbeddedChannel client = new EmbeddedChannel(), server = new EmbeddedChannel();
        try {
            EgressCodec.installClient(client.pipeline(), cCfg);
            EgressCodec.installServer(server.pipeline(), sCfg, InnerProtocol.TRANSPORT_CORE);

            byte[] data = "binary-payload-inside-optional-http-deflate-body".getBytes(StandardCharsets.UTF_8);
            client.writeOutbound(Unpooled.wrappedBuffer(data));
            byte[] wire = readAllOutbound(client);
            String hdr = new String(wire, 0, Math.min(200, wire.length), StandardCharsets.US_ASCII);
            assertTrue(hdr.contains("Content-Encoding: deflate\r\n"));
            assertTrue(hdr.contains("Content-Type: application/json\r\n"));

            server.writeInbound(Unpooled.wrappedBuffer(wire));
            assertArrayEquals(data, takeInbound(server, data.length));
        } finally {
            client.finishAndReleaseAll();
            server.finishAndReleaseAll();
        }
    }

    @Test
    void serverAutoSelectsDisjointModesOnceEnforcesAllowlistAndClosesOnTimeoutOrMalformed() {
        // 1. TRANSPORT_CORE AUTO allows all 3: NONE ('RXTC'), ZLIB_STORED (RFC1950), HTTP ('POST ')
        for (EgressCodec mode : new EgressCodec[]{EgressCodec.NONE, EgressCodec.ZLIB_STORED, EgressCodec.HTTP}) {
            EmbeddedChannel client = new EmbeddedChannel(), server = new EmbeddedChannel();
            try {
                EgressCodec.installClient(client.pipeline(), mode);
                EgressCodec.installServer(server.pipeline(), EgressConfig.serverAutoAll(), InnerProtocol.TRANSPORT_CORE);

                byte[] hello = new byte[]{'R', 'X', 'T', 'C', 2, 0, 1, 0, 99, 100};
                client.writeOutbound(Unpooled.wrappedBuffer(hello));
                transfer(client, server, 3);
                assertNull(server.pipeline().get(EgressCodec.AUTO_DETECTOR_NAME), "AUTO detector must remove itself after 1-shot selection");
                assertEquals(mode, server.attr(EgressCodec.ATTR_SELECTED).get());
                assertArrayEquals(hello, takeInbound(server, hello.length));

                // Server reply uses selected egress encoding
                byte[] ack = new byte[]{'R', 'X', 'T', 'C', 2, 0, 2, 7};
                server.writeOutbound(Unpooled.wrappedBuffer(ack));
                transfer(server, client, 5);
                assertArrayEquals(ack, takeInbound(client, ack.length));
            } finally {
                client.finishAndReleaseAll();
                server.finishAndReleaseAll();
            }
        }

        // 2. Disallowed mode (server AUTO only allows [NONE, HTTP], rejects ZLIB_STORED with zero fallback!)
        EmbeddedChannel storedClient = new EmbeddedChannel(), restrictedServer = new EmbeddedChannel();
        try {
            EgressCodec.installClient(storedClient.pipeline(), EgressCodec.ZLIB_STORED);
            EgressCodec.installServer(restrictedServer.pipeline(),
                    EgressConfig.serverAuto(EgressCodec.NONE, EgressCodec.HTTP), InnerProtocol.TRANSPORT_CORE);
            storedClient.writeOutbound(Unpooled.wrappedBuffer(new byte[]{'R', 'X', 'T', 'C', 2}));
            transfer(storedClient, restrictedServer, 64);
            assertFalse(restrictedServer.isOpen(), "Disallowed mode must close immediately without fallback");
            assertNull(restrictedServer.readInbound());
        } finally {
            storedClient.finishAndReleaseAll();
            restrictedServer.finishAndReleaseAll();
        }

        // 3. Unknown garbage start byte closes immediately
        EmbeddedChannel garbageServer = new EmbeddedChannel();
        try {
            EgressCodec.installServer(garbageServer.pipeline(), EgressConfig.serverAutoAll(), InnerProtocol.TRANSPORT_CORE);
            assertFalse(garbageServer.writeInbound(Unpooled.wrappedBuffer(new byte[]{0x16, 0x03, 0x01, 0x00})));
            assertFalse(garbageServer.isOpen());
        } finally {
            garbageServer.finishAndReleaseAll();
        }

        // 4. Silent slow-loris connection hits detectTimeoutMillis and closes cleanly
        EgressConfig shortTimeout = EgressConfig.serverAutoAll();
        shortTimeout.setDetectTimeoutMillis(200);
        EmbeddedChannel slowServer = new EmbeddedChannel();
        try {
            EgressCodec.installServer(slowServer.pipeline(), shortTimeout, InnerProtocol.TRANSPORT_CORE);
            slowServer.writeInbound(Unpooled.wrappedBuffer(new byte[]{'P', 'O'}));
            assertTrue(slowServer.isOpen());
            slowServer.advanceTimeBy(300, TimeUnit.MILLISECONDS);
            slowServer.runScheduledPendingTasks();
            assertFalse(slowServer.isOpen(), "Slow unauthenticated connection must close on detect timeout");
        } finally {
            slowServer.finishAndReleaseAll();
        }
    }

    @Test
    void malformedZlibStoredClosesFailClosedWithoutDeliveringBytes() {
        EmbeddedChannel receiver = new EmbeddedChannel();
        try {
            EgressCodec.installServer(receiver.pipeline(), EgressCodec.ZLIB_STORED, InnerProtocol.TRANSPORT_CORE);
            assertThrows(DecoderException.class,
                    () -> receiver.writeInbound(Unpooled.wrappedBuffer(new byte[]{1, 2, 3, 4, 5})));
            assertFalse(receiver.isOpen());
            assertNull(receiver.readInbound());
        } finally {
            receiver.finishAndReleaseAll();
        }
    }

    @Test
    void rssSocksAutoPreservesAesAndInnerCompressionForAllEgressModes() {
        SecretKeySpec key = new SecretKeySpec(new byte[32], "HmacSHA256");
        SocksConfig config = new SocksConfig();
        config.setTcpCompressionLevel(3);
        for (EgressCodec mode : new EgressCodec[]{EgressCodec.NONE, EgressCodec.ZLIB_STORED, EgressCodec.HTTP}) {
            EmbeddedChannel client = new EmbeddedChannel(), server = new EmbeddedChannel();
            try {
                EgressCodec.installClient(client.pipeline(), mode);
                EgressCodec.installServer(server.pipeline(), EgressConfig.serverAutoAll(), InnerProtocol.RSS_SOCKS);
                client.pipeline().addLast(new RssHopAes(true, key));
                client.pipeline().addLast(Sockets.newTcpCompressionEncoder(config,
                        io.netty.handler.codec.compression.ZlibWrapper.ZLIB));
                server.pipeline().addLast(new RssTcpCodecSelector(config, key));

                byte[] data = new byte[8192];
                Arrays.fill(data, (byte) 5);
                client.writeOutbound(Unpooled.wrappedBuffer(data));
                transfer(client, server, 13);
                assertFalse(RssHopAes.authenticated(server.pipeline()));
                transfer(server, client, 17);
                int c2sBytes = transfer(client, server, 29);
                assertTrue(c2sBytes > 0 && c2sBytes < data.length / 4);
                assertTrue(RssHopAes.authenticated(server.pipeline()));
                assertEquals(mode, server.attr(EgressCodec.ATTR_SELECTED).get());
                assertArrayEquals(data, takeInbound(server, data.length));
            } finally {
                client.finishAndReleaseAll();
                server.finishAndReleaseAll();
            }
        }
    }

    private static byte[] readAllOutbound(EmbeddedChannel ch) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuf buf;
        while ((buf = ch.readOutbound()) != null) {
            try {
                byte[] arr = new byte[buf.readableBytes()];
                buf.readBytes(arr);
                out.write(arr, 0, arr.length);
            } finally {
                buf.release();
            }
        }
        return out.toByteArray();
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

    private static byte[] takeInbound(EmbeddedChannel ch, int size) {
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
