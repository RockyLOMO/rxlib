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
            TcpWarmPoolKey httpRandom = TcpWarmPoolKey.from(ep, config, "test");
            config.setEgressConfig(EgressConfig.clientHttpFixed("api.example.com", "/v1/custom"));
            TcpWarmPoolKey httpFixed = TcpWarmPoolKey.from(ep, config, "test");
            EgressConfig httpDeflateCfg = EgressConfig.client(EgressCodec.HTTP);
            httpDeflateCfg.setHttpBodyEncoding(HttpBodyEncoding.ZLIB_STORED);
            config.setEgressConfig(httpDeflateCfg);
            TcpWarmPoolKey httpDeflate = TcpWarmPoolKey.from(ep, config, "test");
            assertNotEquals(raw, stored);
            assertNotEquals(raw, httpRandom);
            assertNotEquals(stored, httpRandom);
            assertNotEquals(httpRandom, httpFixed);
            assertNotEquals(httpRandom, httpDeflate);
            assertNotEquals(httpFixed, httpDeflate);
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
            EgressConfig fixedClient = EgressConfig.clientHttpFixed("api.local", "/v1/stream");
            EgressConfig fixedServer = EgressConfig.server(EgressCodec.HTTP);
            fixedServer.setHttpAllowGetMethod(false);
            EgressCodec.installClient(client.pipeline(), fixedClient);
            EgressCodec.installServer(server.pipeline(), fixedServer, InnerProtocol.TRANSPORT_CORE);

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
            String c2sPrefix = new String(c2sWire, 0, Math.min(350, c2sWire.length), StandardCharsets.US_ASCII);
            assertTrue(c2sPrefix.startsWith("POST /v1/stream HTTP/1.1\r\n"), c2sPrefix);
            assertTrue(c2sPrefix.contains("Connection: keep-alive\r\n"), c2sPrefix);
            assertTrue(c2sPrefix.contains("Host: api.local\r\n"), c2sPrefix);
            assertTrue(c2sPrefix.contains("Accept: */*\r\n"), c2sPrefix);
            assertTrue(c2sPrefix.contains("User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36\r\n"), c2sPrefix);
            assertTrue(c2sPrefix.contains("Content-Type: application/json\r\n"), c2sPrefix);
            assertTrue(c2sPrefix.contains("Content-Length: 4096\r\n"), c2sPrefix);

            // Verify Netty's own standard HttpRequestDecoder parses it cleanly!
            assertTrue(stdReqParser.writeInbound(Unpooled.wrappedBuffer(c2sWire)));
            FullHttpRequest parsedReq = stdReqParser.readInbound();
            assertNotNull(parsedReq);
            try {
                assertEquals("application/json", parsedReq.headers().get("Content-Type"));
                assertEquals("api.local", parsedReq.headers().get("Host"));
                assertEquals("*/*", parsedReq.headers().get("Accept"));
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
                assertEquals("Tengine", r1.headers().get("Server"));
                assertNotNull(r1.headers().get("Date"));
                assertTrue(r1.headers().get("Date").endsWith(" GMT"), r1.headers().get("Date"));
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
        tight.setHttpAllowGetMethod(false);
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
            String hdr = new String(wire, 0, Math.min(350, wire.length), StandardCharsets.US_ASCII);
            assertTrue(hdr.contains("Content-Encoding: deflate\r\n"), hdr);
            assertTrue(hdr.contains("Content-Type: application/json\r\n"), hdr);

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
        byte[] psk = new byte[32];
        byte[] userSecret = "rss-plain-secret".getBytes(StandardCharsets.UTF_8);
        org.rx.net.transport.core.TransportCoreCarrier.NonceReplayCache replayCache =
                new org.rx.net.transport.core.TransportCoreCarrier.NonceReplayCache();
        for (boolean aead : new boolean[]{true, false}) {
            for (EgressCodec mode : new EgressCodec[]{EgressCodec.NONE, EgressCodec.ZLIB_STORED, EgressCodec.HTTP}) {
                EmbeddedChannel client = new EmbeddedChannel(), server = new EmbeddedChannel();
                try {
                    EgressCodec.installClient(client.pipeline(), mode);
                    EgressCodec.installServer(server.pipeline(), EgressConfig.serverAutoAll(), InnerProtocol.TRANSPORT_CORE);
                    org.rx.net.transport.core.TransportCoreCarrier.ClientOptions cOpts =
                            new org.rx.net.transport.core.TransportCoreCarrier.ClientOptions(
                                    "rss-u", aead ? psk : userSecret, aead, true, 3,
                                    false, 0, false, null, 0, null, 5000, 1 << 22);
                    org.rx.net.transport.core.TransportCoreCarrier.ServerOptions sOpts =
                            new org.rx.net.transport.core.TransportCoreCarrier.ServerOptions(
                                    psk, peer -> "rss-u".equals(peer) ? userSecret : null, null, false, 1300, 3,
                                    replayCache, true, 5000, 1 << 22, false, false);
                    client.pipeline().addLast(org.rx.net.transport.core.TransportCoreCarrier.clientHandler(cOpts));
                    server.pipeline().addLast(org.rx.net.transport.core.TransportCoreCarrier.serverHandler(sOpts));

                    byte[] data = new byte[8192];
                    Arrays.fill(data, (byte) 5);
                    client.writeOutbound(Unpooled.wrappedBuffer(data));
                    transfer(client, server, 13);
                    assertEquals(aead, org.rx.net.transport.core.TransportCoreCarrier.isAeadAuthenticated(server.pipeline()));
                    transfer(server, client, 17);
                    int c2sBytes = transfer(client, server, 29);
                    assertTrue(c2sBytes > 0 && c2sBytes < data.length / 4);
                    assertEquals(aead, org.rx.net.transport.core.TransportCoreCarrier.isAeadAuthenticated(server.pipeline()));
                    assertEquals(mode, server.attr(EgressCodec.ATTR_SELECTED).get());
                    assertArrayEquals(data, takeInbound(server, data.length));
                } finally {
                    client.finishAndReleaseAll();
                    server.finishAndReleaseAll();
                }
            }
        }
    }

    @Test
    void httpDefaultRandomModeRestoresLegacyHostPathQueryGetPostAndResponseHeaders() {
        EmbeddedChannel client = new EmbeddedChannel(), server = new EmbeddedChannel();
        try {
            EgressCodec.installClient(client.pipeline(), EgressCodec.HTTP);
            EgressCodec.installServer(server.pipeline(), EgressConfig.serverAutoAll(), InnerProtocol.TRANSPORT_CORE);

            int getCount = 0;
            int postCount = 0;
            java.util.Set<String> seenHosts = new java.util.HashSet<String>();
            java.util.Set<String> seenPaths = new java.util.HashSet<String>();
            for (int i = 0; i < 60; i++) {
                byte[] msg = ("RXTC-frame-" + i).getBytes(StandardCharsets.UTF_8);
                client.writeOutbound(Unpooled.wrappedBuffer(msg));
                byte[] wire = readAllOutbound(client);
                String text = new String(wire, 0, wire.length - msg.length, StandardCharsets.US_ASCII);
                if (text.startsWith("GET ")) {
                    getCount++;
                } else if (text.startsWith("POST ")) {
                    postCount++;
                } else {
                    fail("Unexpected HTTP method line: " + text);
                }
                assertTrue(text.contains("Connection: keep-alive\r\n"), text);
                assertTrue(text.contains("Accept: */*\r\n"), text);
                assertTrue(text.contains("User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36\r\n"), text);
                assertTrue(text.contains("Content-Type: application/json\r\n"), text);
                int firstSpace = text.indexOf(' ');
                int httpVer = text.indexOf(" HTTP/1.1\r\n");
                seenPaths.add(text.substring(firstSpace + 1, httpVer));
                int hostIdx = text.indexOf("Host: ");
                int hostEnd = text.indexOf("\r\n", hostIdx);
                seenHosts.add(text.substring(hostIdx + 6, hostEnd));

                // Roundtrip through server AUTO -> HTTP decoder
                server.writeInbound(Unpooled.wrappedBuffer(wire));
                assertArrayEquals(msg, takeInbound(server, msg.length));
            }
            assertEquals(EgressCodec.HTTP, server.attr(EgressCodec.ATTR_SELECTED).get());
            assertTrue(getCount > 0 && postCount > 0, "Random mode must emit mix of GET and POST (get=" + getCount + ", post=" + postCount + ")");
            assertTrue(seenHosts.size() >= 5, "Random hosts should vary: " + seenHosts);
            assertTrue(seenPaths.size() >= 5, "Random paths/queries should vary: " + seenPaths);

            // Server response must carry actual UTC Date + Server: Tengine + Content-Type: application/json
            byte[] resp = "server-reply".getBytes(StandardCharsets.UTF_8);
            server.writeOutbound(Unpooled.wrappedBuffer(resp));
            byte[] respWire = readAllOutbound(server);
            String respHdr = new String(respWire, 0, respWire.length - resp.length, StandardCharsets.US_ASCII);
            assertTrue(respHdr.startsWith("HTTP/1.1 200 OK\r\nDate: "), respHdr);
            assertTrue(respHdr.contains(" GMT\r\nServer: Tengine\r\nContent-Type: application/json\r\n"), respHdr);
            assertTrue(respHdr.contains("Connection: keep-alive\r\n\r\n"), respHdr);
            client.writeInbound(Unpooled.wrappedBuffer(respWire));
            assertArrayEquals(resp, takeInbound(client, resp.length));

            // Direct generator helpers produce valid path & host syntax
            for (int i = 0; i < 20; i++) {
                String path = EgressCodec.generateRandomPath();
                String host = EgressCodec.generateRandomHost();
                assertTrue(path.startsWith("/") && path.indexOf(' ') < 0, path);
                assertTrue(host.contains(".") && host.indexOf(' ') < 0, host);
            }
        } finally {
            client.finishAndReleaseAll();
            server.finishAndReleaseAll();
        }
    }

    @Test
    void serverAutoHandlesCoalescedLargeFirstReadAndSlowHeaderTimeoutAndEofAndPromiseAggregation() {
        // 1. Coalesced first read > maxHeaderBytes MUST succeed under AUTO (header <= 512, body = 16 KiB)
        EgressConfig serverCfg = EgressConfig.serverAutoAll();
        serverCfg.setMaxHeaderBytes(512);
        serverCfg.setMaxBodyBytes(65536);
        serverCfg.setDetectTimeoutMillis(200);

        EmbeddedChannel client = new EmbeddedChannel(), server = new EmbeddedChannel();
        try {
            EgressCodec.installClient(client.pipeline(), EgressCodec.HTTP);
            EgressCodec.installServer(server.pipeline(), serverCfg, InnerProtocol.TRANSPORT_CORE);

            byte[] largeBody = new byte[16 * 1024];
            for (int i = 0; i < largeBody.length; i++) {
                largeBody[i] = (byte) (i * 13 + 3);
            }
            client.writeOutbound(Unpooled.wrappedBuffer(largeBody));
            byte[] coalesced = readAllOutbound(client);
            assertTrue(coalesced.length > serverCfg.getMaxHeaderBytes() * 10, "Single read must far exceed maxHeaderBytes");

            // Feed entire coalesced frame in ONE writeInbound!
            assertTrue(server.writeInbound(Unpooled.wrappedBuffer(coalesced)));
            assertEquals(EgressCodec.HTTP, server.attr(EgressCodec.ATTR_SELECTED).get());
            assertArrayEquals(largeBody, takeInbound(server, largeBody.length));
        } finally {
            client.finishAndReleaseAll();
            server.finishAndReleaseAll();
        }

        // 2. Slow partial HTTP header after AUTO detects HTTP must hit header timeout and close cleanly
        EmbeddedChannel slowHdr = new EmbeddedChannel();
        try {
            EgressCodec.installServer(slowHdr.pipeline(), serverCfg, InnerProtocol.TRANSPORT_CORE);
            slowHdr.writeInbound(Unpooled.copiedBuffer("POST /api/data HTTP/1.1\r\nHost: a.cn\r\n", StandardCharsets.US_ASCII));
            assertEquals(EgressCodec.HTTP, slowHdr.attr(EgressCodec.ATTR_SELECTED).get());
            assertTrue(slowHdr.isOpen());
            slowHdr.advanceTimeBy(300, TimeUnit.MILLISECONDS);
            slowHdr.runScheduledPendingTasks();
            assertFalse(slowHdr.isOpen(), "Slow incomplete HTTP header must close after timeout");
        } finally {
            slowHdr.finishAndReleaseAll();
        }

        // 3. Clean EOF/FIN with truncated HTTP body closes cleanly with zero buffer leaks
        EmbeddedChannel eofCh = new EmbeddedChannel();
        try {
            EgressCodec.installServer(eofCh.pipeline(), EgressConfig.server(EgressCodec.HTTP), InnerProtocol.TRANSPORT_CORE);
            eofCh.writeInbound(Unpooled.copiedBuffer("POST /v1/x HTTP/1.1\r\nHost: a.cn\r\nContent-Length: 100\r\n\r\npartial", StandardCharsets.US_ASCII));
            eofCh.finish();
            assertNull(eofCh.readInbound());
        } finally {
            eofCh.finishAndReleaseAll();
        }

        // 4. Write promise aggregation: failing the header write MUST fail caller's write promise & close channel!
        EmbeddedChannel failCh = new EmbeddedChannel();
        try {
            failCh.pipeline().addLast("failFirstWrite", new io.netty.channel.ChannelOutboundHandlerAdapter() {
                private boolean failedOnce;
                @Override
                public void write(io.netty.channel.ChannelHandlerContext ctx, Object msg, io.netty.channel.ChannelPromise promise) {
                    if (!failedOnce) {
                        failedOnce = true;
                        io.netty.util.ReferenceCountUtil.release(msg);
                        promise.tryFailure(new java.io.IOException("simulated header write failure"));
                        return;
                    }
                    ctx.write(msg, promise);
                }
            });
            EgressCodec.installClient(failCh.pipeline(), EgressCodec.HTTP);
            io.netty.channel.ChannelFuture wf = failCh.writeAndFlush(Unpooled.copiedBuffer("payload", StandardCharsets.UTF_8));
            assertTrue(wf.isDone());
            assertFalse(wf.isSuccess(), "Header write failure must fail caller write promise");
            assertFalse(failCh.isOpen(), "Header write failure must close channel");
        } finally {
            failCh.finishAndReleaseAll();
        }

        // 5. Multi-chunk split (> maxBodyBytes) aggregates write promise cleanly and delivers intact bytes
        EgressConfig chunkClientCfg = EgressConfig.client(EgressCodec.HTTP);
        chunkClientCfg.setMaxBodyBytes(1024);
        EgressConfig chunkServerCfg = EgressConfig.server(EgressCodec.HTTP);
        chunkServerCfg.setMaxBodyBytes(1024);
        EmbeddedChannel cc = new EmbeddedChannel(), cs = new EmbeddedChannel();
        try {
            EgressCodec.installClient(cc.pipeline(), chunkClientCfg);
            EgressCodec.installServer(cs.pipeline(), chunkServerCfg, InnerProtocol.TRANSPORT_CORE);
            byte[] multi = new byte[3500];
            for (int i = 0; i < multi.length; i++) {
                multi[i] = (byte) (i & 0xff);
            }
            io.netty.channel.ChannelFuture wf = cc.writeAndFlush(Unpooled.wrappedBuffer(multi));
            assertTrue(wf.isSuccess(), "Multi-chunk write promise must succeed after all chunks complete");
            transfer(cc, cs, 97);
            assertArrayEquals(multi, takeInbound(cs, multi.length));
        } finally {
            cc.finishAndReleaseAll();
            cs.finishAndReleaseAll();
        }
    }

    @Test
    void compressionRunsOnceAboveAdaptiveStreamAndEgressWrapsOnlyPhysicalTcp() throws Exception {
        for (EgressCodec outerMode : new EgressCodec[]{EgressCodec.ZLIB_STORED, EgressCodec.HTTP}) {
            byte[] key = new byte[32];
            java.util.concurrent.atomic.AtomicInteger compressCalls = new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(1_000_000L);
            java.io.ByteArrayOutputStream deliveredToServerInner = new java.io.ByteArrayOutputStream();
            java.util.ArrayDeque<ByteBuf> c2sUdp = new java.util.ArrayDeque<ByteBuf>();
            java.util.ArrayDeque<ByteBuf> s2cUdp = new java.util.ArrayDeque<ByteBuf>();
            int[] udpFramesSent = {0};
            long[] tcpInnerBytes = {0}, tcpWireBytes = {0};

            EmbeddedChannel clientInner = new EmbeddedChannel(), serverInner = new EmbeddedChannel();
            EmbeddedChannel clientTcp = new EmbeddedChannel(), serverTcp = new EmbeddedChannel();
            org.rx.net.rss.tcp.AdaptiveTcpStream clientStream = null, serverStream = null;
            ByteBuf c2sTcpRx = Unpooled.buffer(), s2cTcpRx = Unpooled.buffer();
            try {
                EgressCodec.installClient(clientTcp.pipeline(), outerMode);
                EgressCodec.installServer(serverTcp.pipeline(), EgressConfig.serverAutoAll(), InnerProtocol.TRANSPORT_CORE);
                org.rx.net.transport.core.TransportCoreCarrier.ClientOptions cOpts =
                        new org.rx.net.transport.core.TransportCoreCarrier.ClientOptions(
                                "u", key, true, true, 3, false, 0, false, null, 0, null, 5000, 1 << 22);
                org.rx.net.transport.core.TransportCoreCarrier.ServerOptions sOpts =
                        new org.rx.net.transport.core.TransportCoreCarrier.ServerOptions(
                                key, p -> null, null, false, 1300, 3,
                                new org.rx.net.transport.core.TransportCoreCarrier.NonceReplayCache(),
                                true, 5000, 1 << 22, false, false);
                clientInner.pipeline().addLast(org.rx.net.transport.core.TransportCoreCarrier.clientHandler(cOpts));
                clientInner.pipeline().addLast(new io.netty.channel.ChannelOutboundHandlerAdapter() {
                    @Override
                    public void write(io.netty.channel.ChannelHandlerContext ctx, Object msg, io.netty.channel.ChannelPromise promise) throws Exception {
                        if (msg instanceof ByteBuf && ((ByteBuf) msg).isReadable()) {
                            compressCalls.incrementAndGet();
                        }
                        super.write(ctx, msg, promise);
                    }
                });
                serverInner.pipeline().addLast(org.rx.net.transport.core.TransportCoreCarrier.serverHandler(sOpts));

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
                            // UDP fast path must NEVER be wrapped with HTTP or RFC1950 outer egress!
                            if (udpFrame.readableBytes() >= 4) {
                                int b0 = udpFrame.getUnsignedByte(udpFrame.readerIndex());
                                assertNotEquals('P', b0, "UDP fast path must never carry HTTP egress");
                                assertNotEquals('G', b0, "UDP fast path must never carry HTTP egress");
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

                // Client initiates physical TCP handshake ("RXTC") first so server AUTO selects outerMode before AdaptiveTcpStream runs
                clientTcp.writeOutbound(Unpooled.wrappedBuffer(new byte[]{'R', 'X', 'T', 'C'}));
                tcpWireBytes[0] += transfer(clientTcp, serverTcp, 2);
                assertArrayEquals(new byte[]{'R', 'X', 'T', 'C'}, takeInbound(serverTcp, 4));
                assertEquals(outerMode, serverTcp.attr(EgressCodec.ATTR_SELECTED).get());

                // Activate AdaptiveTcpStream UDP fast path via PING/PONG
                clientStream.tick();
                serverStream.tick();
                drainAdaptiveUdp(c2sUdp, serverStream);
                drainAdaptiveUdp(s2cUdp, clientStream);
                drainAdaptiveUdp(c2sUdp, serverStream);
                assertTrue(clientStream.usingUdp());
                assertTrue(serverStream.usingUdp());

                // Trigger TransportCoreCarrier handshake + 3 compressible business chunks (24 KiB)
                byte[] expected = new byte[24 * 1024];
                for (int i = 0; i < expected.length; i++) {
                    expected[i] = (byte) ('A' + (i % 4));
                }
                clientInner.writeOutbound(Unpooled.wrappedBuffer(expected, 0, 8192));
                clientInner.writeOutbound(Unpooled.wrappedBuffer(expected, 8192, 8192));
                clientInner.writeOutbound(Unpooled.wrappedBuffer(expected, 16384, 8192));

                // Pump inner -> adaptive -> UDP/TCP until handshake & business payload complete
                for (int round = 0; round < 12; round++) {
                    ByteBuf outBuf;
                    while ((outBuf = clientInner.readOutbound()) != null) {
                        try {
                            clientStream.write(outBuf);
                        } finally {
                            outBuf.release();
                        }
                    }
                    while ((outBuf = serverInner.readOutbound()) != null) {
                        try {
                            serverStream.write(outBuf);
                        } finally {
                            outBuf.release();
                        }
                    }
                    // Drop 1 UDP packet intentionally on round 2 to force TCP repair over outer EgressCodec!
                    if (round == 2 && !c2sUdp.isEmpty()) {
                        c2sUdp.poll().release();
                    }
                    drainAdaptiveUdp(c2sUdp, serverStream);
                    drainAdaptiveUdp(s2cUdp, clientStream);
                    tcpWireBytes[0] += transfer(clientTcp, serverTcp, 73);
                    transfer(serverTcp, clientTcp, 73);
                    drainAdaptiveTcp(serverTcp, c2sTcpRx, serverStream);
                    drainAdaptiveTcp(clientTcp, s2cTcpRx, clientStream);
                    clock.addAndGet(150_000_000L);
                    clientStream.tick();
                    serverStream.tick();
                }

                assertEquals(3, compressCalls.get(), "Business compression runs exactly once per write above AdaptiveTcpStream");
                assertTrue(udpFramesSent[0] > 0, "UDP fast path must carry business frames directly");
                assertTrue(tcpInnerBytes[0] > 0 && tcpWireBytes[0] > tcpInnerBytes[0],
                        "Physical TCP repair must pass through outer " + outerMode + " egress");
                assertEquals(outerMode, serverTcp.attr(EgressCodec.ATTR_SELECTED).get());
                assertTrue(org.rx.net.transport.core.TransportCoreCarrier.isAeadAuthenticated(serverInner.pipeline()));
                assertTrue(deliveredToServerInner.size() < expected.length / 4, "Inner business stream must stay compressed");
                assertArrayEquals(expected, takeInbound(serverInner, expected.length));
            } finally {
                c2sTcpRx.release();
                s2cTcpRx.release();
                while (!c2sUdp.isEmpty()) c2sUdp.poll().release();
                while (!s2cUdp.isEmpty()) s2cUdp.poll().release();
                if (clientStream != null) clientStream.close();
                if (serverStream != null) serverStream.close();
                clientInner.finishAndReleaseAll();
                serverInner.finishAndReleaseAll();
                clientTcp.finishAndReleaseAll();
                serverTcp.finishAndReleaseAll();
            }
        }
    }

    private static void drainAdaptiveUdp(java.util.ArrayDeque<ByteBuf> queue, org.rx.net.rss.tcp.AdaptiveTcpStream target) {
        ByteBuf pkt;
        while ((pkt = queue.poll()) != null) {
            try {
                target.udp(pkt);
            } finally {
                pkt.release();
            }
        }
    }

    private static void drainAdaptiveTcp(EmbeddedChannel ch, ByteBuf acc, org.rx.net.rss.tcp.AdaptiveTcpStream target) {
        ByteBuf in;
        while ((in = ch.readInbound()) != null) {
            try {
                acc.writeBytes(in);
            } finally {
                in.release();
            }
        }
        while (acc.readableBytes() >= 2) {
            int len = acc.getUnsignedShort(acc.readerIndex());
            if (acc.readableBytes() < 2 + len) {
                break;
            }
            acc.skipBytes(2);
            ByteBuf frame = acc.readRetainedSlice(len);
            try {
                target.tcp(frame);
            } finally {
                frame.release();
            }
        }
        acc.discardSomeReadBytes();
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
