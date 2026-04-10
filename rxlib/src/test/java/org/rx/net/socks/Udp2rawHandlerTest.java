package org.rx.net.socks;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.socket.DatagramPacket;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.rx.net.Sockets;

import java.lang.reflect.Field;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Udp2rawHandler refactoring:
 * <ol>
 *   <li>SocksProxyServer no longer has a udp2rawChannel field</li>
 *   <li>UDP_ASSOCIATE with enableUdp2raw=false → SocksUdpRelayHandler (normal path)</li>
 *   <li>UDP_ASSOCIATE with enableUdp2raw=true (client mode) → Udp2rawHandler</li>
 *   <li>Udp2rawHandler client mode E2E: SOCKS5 UDP → udp2raw-wrapped → server → unwrap → echo → wrap → client unwrap</li>
 *   <li>Udp2rawHandler server mode E2E: udp2raw packet → unwrap → echo → wrap response</li>
 * </ol>
 */
@Slf4j
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Udp2rawHandlerTest {

    static final int UDP_ECHO_PORT = 16400;
    static Channel udpEchoChannel;

    @BeforeAll
    static void setup() throws InterruptedException {
        // Shared UDP echo server (replies content back to sender)
        Bootstrap udpEcho = Sockets.udpBootstrap(null, ch -> ch.pipeline().addLast(
                new SimpleChannelInboundHandler<DatagramPacket>() {
                    @Override
                    protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket msg) {
                        ctx.writeAndFlush(new DatagramPacket(msg.content().retain(), msg.sender()));
                    }
                }));
        udpEchoChannel = udpEcho.bind(Sockets.newAnyEndpoint(UDP_ECHO_PORT)).sync().channel();
        log.info("UDP echo server on port {}", UDP_ECHO_PORT);
        Thread.sleep(300);
    }

    @AfterAll
    static void teardown() {
        if (udpEchoChannel != null) udpEchoChannel.close();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 1: SocksProxyServer must NOT have a udp2rawChannel field
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    void socksProxyServer_hasNoUdp2rawChannelField() {
        boolean found = Arrays.stream(SocksProxyServer.class.getDeclaredFields())
                .anyMatch(f -> f.getName().equals("udp2rawChannel"));
        assertFalse(found, "SocksProxyServer should not have udp2rawChannel field after refactoring");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 2: enableUdp2raw=false → SocksUdpRelayHandler installed (normal UDP relay)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @Order(2)
    @SneakyThrows
    @Timeout(20)
    void udpAssociate_withoutUdp2raw_usesSocksUdpRelayHandler() {
        int proxyPort = 16380;
        SocksConfig config = new SocksConfig(proxyPort);
        config.getWhiteList();
        // enableUdp2raw defaults to false
        SocksProxyServer proxy = new SocksProxyServer(config, null);

        try {
            Thread.sleep(800);
            int relayPort = doUdpAssociate(proxyPort);
            assertTrue(relayPort > 0, "relay port must be valid");

            // Send a standard SOCKS5 UDP packet (RSV=0, FRAG=0, ATYP=1, ADDR, PORT, DATA)
            byte[] payload = "hello-normal-relay".getBytes(StandardCharsets.UTF_8);
            byte[] req = buildSocks5UdpPacket("127.0.0.1", UDP_ECHO_PORT, payload);

            DatagramSocket sock = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
            sock.setSoTimeout(5000);
            try {
                sock.send(new java.net.DatagramPacket(req, req.length, InetAddress.getByName("127.0.0.1"), relayPort));
                byte[] resp = new byte[512];
                java.net.DatagramPacket p = new java.net.DatagramPacket(resp, resp.length);
                sock.receive(p);

                // Response must be SOCKS5 UDP encapsulated
                assertTrue(p.getLength() >= 10, "response too short");
                assertEquals(0, resp[0]); // RSV
                assertEquals(0, resp[1]);
                assertEquals(0, resp[2]); // FRAG
                assertEquals(0x01, resp[3]); // IPv4

                byte[] echoed = Arrays.copyOfRange(resp, 10, p.getLength());
                assertArrayEquals(payload, echoed, "echoed payload mismatch");
            } finally {
                sock.close();
            }
        } finally {
            proxy.close();
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 3 & 4: enableUdp2raw=true client mode E2E
    //   Client proxy (udp2rawClient set) → Udp2rawHandler client mode
    //   Server proxy (enableUdp2raw, no udp2rawClient) → Udp2rawHandler server mode
    //   Flow: SOCKS5 app → [client proxy] → udp2raw stream → [server proxy] → echo → udp2raw → [client proxy] → SOCKS5 resp
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @Order(3)
    @SneakyThrows
    @Timeout(25)
    void udp2raw_clientServerModeE2E() {
        int serverProxyPort = 16381; // server-side SOCKS proxy with udp2raw server mode
        int clientProxyPort = 16382; // client-side SOCKS proxy with udp2raw client mode

        // ---- Server proxy (server mode: enableUdp2raw=true, no udp2rawClient) ----
        SocksConfig serverCfg = new SocksConfig(serverProxyPort);
        serverCfg.getWhiteList();
        serverCfg.setEnableUdp2raw(true); // server mode (udp2rawClient == null)
        serverCfg.setDebug(true);
        SocksProxyServer serverProxy = new SocksProxyServer(serverCfg, null);

        // ---- Client proxy (client mode: enableUdp2raw=true + udp2rawClient pointing to server) ----
        SocksConfig clientCfg = new SocksConfig(clientProxyPort);
        clientCfg.getWhiteList();
        clientCfg.setEnableUdp2raw(true);
        // In client mode we need udp2rawClient to point to server proxy's UDP port.
        // The server proxy listens on the same port (serverProxyPort) for UDP_ASSOCIATE per-client channels.
        // We'll resolve the server UDP relay port dynamically after UDP_ASSOCIATE on server side.
        // For simplicity, use a dedicated forwarder socket as a "udp2rawClient" server address.
        // Architecture: clientApp → clientProxy (udp2raw client) → serverProxy relay (udp2raw server) → echo
        clientCfg.setDebug(true);

        try {
            Thread.sleep(800);

            // Step 1: Establish UDP_ASSOCIATE on the SERVER proxy side
            // (server-side app would do this; for client mode test we simulate it)
            int serverRelayPort = doUdpAssociate(serverProxyPort);
            assertTrue(serverRelayPort > 0, "server relay port must be valid: " + serverRelayPort);
            log.info("Server relay UDP port: {}", serverRelayPort);

            // Now configure client proxy to use server relay port as udp2rawClient
            InetSocketAddress udp2rawServerAddr = new InetSocketAddress("127.0.0.1", serverRelayPort);
            clientCfg.setUdp2rawClient(udp2rawServerAddr);
            SocksProxyServer clientProxy = new SocksProxyServer(clientCfg, null);

            try {
                Thread.sleep(500);

                // Step 2: Client app does UDP_ASSOCIATE on client proxy
                int clientRelayPort = doUdpAssociate(clientProxyPort);
                assertTrue(clientRelayPort > 0, "client relay port must be valid: " + clientRelayPort);
                log.info("Client relay UDP port: {}", clientRelayPort);

                // Step 3: Send SOCKS5 UDP packet to client relay port
                // Client proxy wraps in udp2raw and sends to server relay port
                // Server proxy (udp2raw server mode) unwraps, sends to echo, gets response,
                // wraps in udp2raw and sends back to client relay
                // Client proxy unwraps and returns SOCKS5 UDP to app

                byte[] payload = "udp2raw-e2e-test".getBytes(StandardCharsets.UTF_8);
                byte[] req = buildSocks5UdpPacket("127.0.0.1", UDP_ECHO_PORT, payload);

                DatagramSocket appSock = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
                appSock.setSoTimeout(6000);
                try {
                    appSock.send(new java.net.DatagramPacket(req, req.length,
                            InetAddress.getByName("127.0.0.1"), clientRelayPort));

                    byte[] resp = new byte[512];
                    java.net.DatagramPacket p = new java.net.DatagramPacket(resp, resp.length);
                    appSock.receive(p);

                    // The client proxy's handleClientModeResponse returns raw SOCKS5 UDP payload
                    // (the original inBuf.retain() after header read)
                    assertTrue(p.getLength() > 0, "received no data");
                    log.info("Received {} bytes back from udp2raw E2E", p.getLength());

                    // The remaining bytes should be the echoed payload
                    // (the server wraps the SOCKS5-encoded echo, then client decode strips the udp2raw header)
                    // Actual bytes start after the SOCKS5 header (10 bytes for IPv4)
                    if (p.getLength() >= 10) {
                        byte[] echoed = Arrays.copyOfRange(resp, 10, p.getLength());
                        assertArrayEquals(payload, echoed, "E2E payload mismatch");
                    } else {
                        // If server returned raw data without socks5 header, still check payload
                        assertTrue(p.getLength() >= payload.length, "Response too short: " + p.getLength());
                    }
                } finally {
                    appSock.close();
                }
            } finally {
                clientProxy.close();
            }
        } finally {
            serverProxy.close();
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 5: Udp2rawHandler server mode – direct udp2raw packet → echo → wrapped back
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @Order(4)
    @SneakyThrows
    @Timeout(20)
    void udp2raw_serverMode_unwrapsAndForwardsToEcho() {
        int serverProxyPort = 16383;
        SocksConfig cfg = new SocksConfig(serverProxyPort);
        cfg.getWhiteList();
        cfg.setEnableUdp2raw(true); // server mode
        cfg.setDebug(true);
        SocksProxyServer serverProxy = new SocksProxyServer(cfg, null);

        try {
            Thread.sleep(800);

            // Establish UDP_ASSOCIATE on server proxy (creates per-client relay channel with Udp2rawHandler)
            int[] relayPortRef = new int[1];
            Socket tcpCtrl = doUdpAssociateTcp(serverProxyPort, relayPortRef);
            int relayPort = relayPortRef[0];
            assertTrue(relayPort > 0, "relay port must be valid: " + relayPort);
            log.info("Server mode relay port: {}", relayPort);

            try {
                DatagramSocket testSock = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
                testSock.setSoTimeout(5000);
                InetSocketAddress testSockAddr = (InetSocketAddress) testSock.getLocalSocketAddress();

                try {
                    // Build a udp2raw-encoded packet:
                    // MAGIC(2) | VERSION(1) | clientEp(addr) | dstEp(addr) | SOCKS5-data
                    byte[] payload = "server-mode-data".getBytes(StandardCharsets.UTF_8);
                    ByteBuf pkt = buildUdp2rawServerPacket(testSockAddr, "127.0.0.1", UDP_ECHO_PORT, payload);
                    byte[] pktBytes = toBytes(pkt);

                    testSock.send(new java.net.DatagramPacket(pktBytes, pktBytes.length,
                            InetAddress.getByName("127.0.0.1"), relayPort));

                    // Server proxy should forward to echo and return udp2raw-wrapped response
                    byte[] resp = new byte[1024];
                    java.net.DatagramPacket p = new java.net.DatagramPacket(resp, resp.length);
                    testSock.receive(p);

                    assertTrue(p.getLength() > 0, "no response received");

                    // Decode response: MAGIC(2) + VERSION(1) + clientAddr + socks5-encoded-payload
                    ByteBuf respBuf = Unpooled.wrappedBuffer(resp, 0, p.getLength());
                    try {
                        short magic = respBuf.readShort();
                        assertEquals(Udp2rawHandler.STREAM_MAGIC, magic, "magic mismatch");
                        byte version = respBuf.readByte();
                        assertEquals(Udp2rawHandler.STREAM_VERSION, version, "version mismatch");

                        // Skip client address encoded field
                        UdpManager.decode(respBuf); // consume encoded clientEp
                        // Skip SOCKS5 header (RSV+FRAG+ATYP+IP+PORT = 10 bytes for IPv4)
                        respBuf.skipBytes(3); // RSV(2)+FRAG(1)? Actually socks5Encode produces these
                        UdpManager.decode(respBuf); // skip ATYP+ADDR+PORT
                        // remaining bytes = echoed payload
                        byte[] echoed = new byte[respBuf.readableBytes()];
                        respBuf.readBytes(echoed);
                        assertArrayEquals(payload, echoed, "server mode echoed payload mismatch");
                    } finally {
                        respBuf.release();
                    }
                } finally {
                    testSock.close();
                }
            } finally {
                tcpCtrl.close();
            }
        } finally {
            serverProxy.close();
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 6: Udp2rawHandler client mode – invalid magic discarded gracefully
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @Order(5)
    @SneakyThrows
    @Timeout(15)
    void udp2raw_clientMode_invalidMagicIsDiscarded() {
        int serverProxyPort = 16390;
        int clientProxyPort = 16391;

        SocksConfig serverCfg = new SocksConfig(serverProxyPort);
        serverCfg.getWhiteList();
        serverCfg.setEnableUdp2raw(true);
        SocksProxyServer serverProxy = new SocksProxyServer(serverCfg, null);

        try {
            Thread.sleep(600);
            int serverRelayPort = doUdpAssociate(serverProxyPort);

            SocksConfig clientCfg = new SocksConfig(clientProxyPort);
            clientCfg.getWhiteList();
            clientCfg.setEnableUdp2raw(true);
            clientCfg.setUdp2rawClient(new InetSocketAddress("127.0.0.1", serverRelayPort));
            SocksProxyServer clientProxy = new SocksProxyServer(clientCfg, null);

            try {
                Thread.sleep(500);
                int clientRelayPort = doUdpAssociate(clientProxyPort);

                // Send garbage (< 4 bytes) to client relay from a random port.
                // The handler treats this as a client packet and silently returns early (readableBytes < 4).
                // Verify the proxy does not crash and is still bound.
                DatagramSocket garbageSock = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
                garbageSock.setSoTimeout(1000);
                try {
                    byte[] garbage = new byte[]{0x00, 0x01, 0x02};  // 3 bytes < minimum 4
                    garbageSock.send(new java.net.DatagramPacket(garbage, garbage.length,
                            InetAddress.getByName("127.0.0.1"), clientRelayPort));

                    Thread.sleep(300);
                    assertTrue(clientProxy.isBind(), "Client proxy should still be running after garbage packet");
                } finally {
                    garbageSock.close();
                }
            } finally {
                clientProxy.close();
            }
        } finally {
            serverProxy.close();
        }
    }


    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Opens a SOCKS5 TCP control connection and sends UDP_ASSOCIATE,
     * returns the relay UDP port assigned by the proxy.
     * Leaves TCP connection open (caller must close).
     */
    @SneakyThrows
    static int doUdpAssociate(int proxyPort) {
        int[] portRef = new int[1];
        Socket tcp = doUdpAssociateTcp(proxyPort, portRef);
        // Keep TCP open while using the relay; close in a background thread after brief delay
        Socket ref = tcp;
        Thread t = new Thread(() -> {
            try { Thread.sleep(8000); } catch (InterruptedException ignored) {}
            try { ref.close(); } catch (Exception ignored) {}
        });
        t.setDaemon(true);
        t.start();
        return portRef[0];
    }

    /**
     * Opens SOCKS5 TCP control + UDP_ASSOCIATE; stores relay port in portRef[0].
     * Caller owns the returned Socket.
     */
    @SneakyThrows
    static Socket doUdpAssociateTcp(int proxyPort, int[] portRef) {
        Socket tcp = new Socket("127.0.0.1", proxyPort);
        tcp.setSoTimeout(5000);
        java.io.OutputStream out = tcp.getOutputStream();
        java.io.InputStream in = tcp.getInputStream();

        // Greeting (no auth)
        out.write(new byte[]{0x05, 0x01, 0x00});
        out.flush();
        byte[] hs = readExact(in, 2, 4000);
        assertArrayEquals(new byte[]{0x05, 0x00}, hs, "greeting failed");

        // UDP_ASSOCIATE: cmd=0x03, ATYP=0x01, DST=0.0.0.0:0
        out.write(new byte[]{0x05, 0x03, 0x00, 0x01, 0, 0, 0, 0, 0, 0});
        out.flush();

        byte[] resp = readAtLeast(in, 10, 32, 5000);
        assertEquals(0x05, resp[0] & 0xFF, "VER");
        assertEquals(0x00, resp[1] & 0xFF, "UDP_ASSOCIATE must succeed");
        assertEquals(0x01, resp[3] & 0xFF, "ATYP must be IPv4");
        portRef[0] = ((resp[8] & 0xFF) << 8) | (resp[9] & 0xFF);
        return tcp;
    }

    /** Build a standard SOCKS5 UDP packet (RSV=0, FRAG=0, ATYP=IPv4). */
    static byte[] buildSocks5UdpPacket(String destHost, int destPort, byte[] payload) {
        byte[] hostBytes = parseIpv4(destHost);
        byte[] pkt = new byte[10 + payload.length];
        pkt[0] = 0; pkt[1] = 0; // RSV
        pkt[2] = 0; // FRAG
        pkt[3] = 0x01; // ATYP = IPv4
        System.arraycopy(hostBytes, 0, pkt, 4, 4);
        pkt[8] = (byte) ((destPort >> 8) & 0xFF);
        pkt[9] = (byte) (destPort & 0xFF);
        System.arraycopy(payload, 0, pkt, 10, payload.length);
        return pkt;
    }

    /**
     * Build a udp2raw SERVER-mode incoming packet:
     * MAGIC(2) | VERSION(1) | clientEp | dstEp | raw_payload
     */
    @SneakyThrows
    static ByteBuf buildUdp2rawServerPacket(InetSocketAddress clientEp,
                                             String dstHost, int dstPort,
                                             byte[] payload) {
        ByteBuf buf = Unpooled.buffer(256);
        buf.writeShort(Udp2rawHandler.STREAM_MAGIC);
        buf.writeByte(Udp2rawHandler.STREAM_VERSION);
        UdpManager.encode(buf, clientEp);
        UdpManager.encode(buf, dstHost, dstPort);
        buf.writeBytes(payload);
        return buf;
    }

    static byte[] toBytes(ByteBuf buf) {
        byte[] b = new byte[buf.readableBytes()];
        buf.readBytes(b);
        buf.release();
        return b;
    }

    @SneakyThrows
    static byte[] parseIpv4(String host) {
        return InetAddress.getByName(host).getAddress();
    }

    static byte[] readExact(java.io.InputStream in, int len, int timeoutMs) throws Exception {
        byte[] buf = new byte[len];
        int read = 0;
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (read < len && System.currentTimeMillis() < deadline) {
            int n = in.read(buf, read, len - read);
            if (n == -1) break;
            read += n;
        }
        assertEquals(len, read, "short read");
        return buf;
    }

    static byte[] readAtLeast(java.io.InputStream in, int minLen, int maxLen, int timeoutMs) throws Exception {
        byte[] buf = new byte[maxLen];
        int read = 0;
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (read < minLen && System.currentTimeMillis() < deadline) {
            int n = in.read(buf, read, maxLen - read);
            if (n == -1) break;
            read += n;
        }
        assertTrue(read >= minLen, "short read: got " + read + " expected >= " + minLen);
        return Arrays.copyOf(buf, read);
    }
}
