package org.rx.net.socks;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.socket.DatagramPacket;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.Sockets;
import org.rx.net.socks.upstream.SocksUdpUpstream;
import org.rx.net.support.UnresolvedEndpoint;
import org.rx.net.support.UpstreamSupport;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

@Slf4j
class SocksProxyServerIntegrationTest {
    static final int TCP_ECHO_PORT = 15299;
    static final int UDP_ECHO_PORT = 15300;
    static ServerBootstrap tcpEchoBootstrap;
    static Channel tcpEchoChannel;
    static Channel udpEchoChannel;
    static Bootstrap udpEchoBootstrap;

    @BeforeAll
    static void setup() throws Exception {
        tcpEchoBootstrap = Sockets.serverBootstrap(ch -> ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                ctx.writeAndFlush(msg);
            }
        }));
        tcpEchoChannel = tcpEchoBootstrap.bind(Sockets.newAnyEndpoint(TCP_ECHO_PORT)).sync().channel();

        udpEchoBootstrap = Sockets.udpBootstrap(null, ob -> ob.pipeline().addLast(new SimpleChannelInboundHandler<DatagramPacket>() {
            @Override
            protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket msg) {
                // Echo back to sender
                ByteBuf content = msg.content().retain();
                ctx.writeAndFlush(new DatagramPacket(content, msg.sender()));
            }
        }));
        udpEchoChannel = udpEchoBootstrap.bind(Sockets.newAnyEndpoint(UDP_ECHO_PORT)).sync().channel();

        Thread.sleep(300);
        // Pre-load to avoid blocking Netty worker threads during first init
        Socks5InitialRequestHandler.DEFAULT.hashCode();
    }

    @AfterAll
    static void teardown() {
        if (tcpEchoChannel != null) tcpEchoChannel.close();
        if (tcpEchoBootstrap != null) Sockets.closeBootstrap(tcpEchoBootstrap);
        if (udpEchoChannel != null) udpEchoChannel.close();
    }

    @Test
    @SneakyThrows
    @Timeout(value = 15)
    void socks5TcpConnect_withPasswordAuth_e2e() {
        int proxyPort = 15280;
        SocksUser usr = new SocksUser("u1");
        usr.setPassword("p1");
        usr.setIpLimit(-1);

        SocksConfig config = new SocksConfig(proxyPort);
        config.getWhiteList(); // Trigger lazy init in calling thread
        SocksProxyServer proxy = new SocksProxyServer(config, new DefaultSocksAuthenticator(Collections.singletonList(usr)));

        try {
            Thread.sleep(1000);
            try (Socket s = new Socket("127.0.0.1", proxyPort)) {
                s.setSoTimeout(4000);
                OutputStream out = s.getOutputStream();
                InputStream in = s.getInputStream();

                // 1) greeting supports username/password auth (0x02)
                log.info("Client sending greeting...");
                out.write(new byte[]{0x05, 0x01, 0x02});
                out.flush();
                byte[] hs = readExact(in, 2, 8000);
                assertArrayEquals(new byte[]{0x05, 0x02}, hs);
                log.info("Client received handshake response");

                // 2) auth subnegotiation (RFC1929)
                byte[] u = "u1".getBytes(StandardCharsets.US_ASCII);
                byte[] p = "p1".getBytes(StandardCharsets.US_ASCII);
                ByteBuf auth = Unpooled.buffer();
                auth.writeByte(0x01);
                auth.writeByte(u.length);
                auth.writeBytes(u);
                auth.writeByte(p.length);
                auth.writeBytes(p);
                log.info("Client sending auth...");
                out.write(toBytes(auth));
                out.flush();
                byte[] authResp = readExact(in, 2, 8000);
                assertArrayEquals(new byte[]{0x01, 0x00}, authResp);
                log.info("Client auth success");

                // 3) connect to tcp echo server
                out.write(buildSocks5ConnectReqIpv4("127.0.0.1", TCP_ECHO_PORT));
                out.flush();
                byte[] conn = readAtLeast(in, 4, 10, 3000);
                assertEquals(0x05, conn[0] & 0xFF);
                assertEquals(0x00, conn[1] & 0xFF);

                // 4) payload roundtrip
                String message = "auth-tcp-e2e";
                out.write(message.getBytes(StandardCharsets.UTF_8));
                out.flush();
                byte[] back = readExact(in, message.getBytes(StandardCharsets.UTF_8).length, 5000);
                assertEquals(message, new String(back, StandardCharsets.UTF_8));

                // 5) Additional rounds
                for (int i = 0; i < 10; i++) {
                    String loopMsg = "loop-msg-" + i;
                    out.write(loopMsg.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    byte[] loopBack = readExact(in, loopMsg.getBytes(StandardCharsets.UTF_8).length, 5000);
                    assertEquals(loopMsg, new String(loopBack, StandardCharsets.UTF_8));
                }
            }
        } finally {
            proxy.close();
        }
    }

    @Test
    @SneakyThrows
    @Timeout(value = 15)
    void socks5UdpRelay_e2e() {
        // RFC 1928: UDP relay requires a TCP control connection with UDP_ASSOCIATE command first.
        int proxyPort = 15281;
        SocksConfig config = new SocksConfig(proxyPort);
        config.getWhiteList(); // Trigger lazy init in calling thread
        SocksProxyServer proxy = new SocksProxyServer(config, null);

        try {
            Thread.sleep(1000);

            // 1) Establish TCP control connection and request UDP_ASSOCIATE
            Socket tcp = new Socket("127.0.0.1", proxyPort);
            tcp.setSoTimeout(4000);
            OutputStream tcpOut = tcp.getOutputStream();
            InputStream tcpIn = tcp.getInputStream();

            // Greeting: no auth
            tcpOut.write(new byte[]{0x05, 0x01, 0x00});
            tcpOut.flush();
            byte[] hsResp = readExact(tcpIn, 2, 4000);
            assertArrayEquals(new byte[]{0x05, 0x00}, hsResp);

            // UDP_ASSOCIATE request (0x03), DST = 0.0.0.0:0
            tcpOut.write(new byte[]{0x05, 0x03, 0x00, 0x01, 0, 0, 0, 0, 0, 0});
            tcpOut.flush();

            // Read response: VER(1) + REP(1) + RSV(1) + ATYP(1) + BND.ADDR + BND.PORT
            byte[] udpAssocResp = readAtLeast(tcpIn, 10, 32, 4000);
            assertEquals(0x05, udpAssocResp[0] & 0xFF, "VER");
            assertEquals(0x00, udpAssocResp[1] & 0xFF, "REP must be SUCCESS");
            // ATYP == 0x01 (IPv4): BND.ADDR(4) + BND.PORT(2)
            assertEquals(0x01, udpAssocResp[3] & 0xFF, "ATYP must be IPv4");
            int relayPort = ((udpAssocResp[8] & 0xFF) << 8) | (udpAssocResp[9] & 0xFF);
            assertTrue(relayPort > 0 && relayPort < 65536, "relayPort must be valid: " + relayPort);
            log.info("UDP relay port: {}", relayPort);

            // 2) Send UDP datagrams to the relay port
            DatagramSocket sock = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
            sock.setSoTimeout(4000);
            try {
                byte[] payload = "udp-e2e".getBytes(StandardCharsets.UTF_8);
                ByteBuf header = Unpooled.buffer();
                header.writeZero(3); // RSV(2)+FRAG(1)
                header.writeByte(0x01); // IPv4
                header.writeBytes(new byte[]{127, 0, 0, 1});
                header.writeShort(UDP_ECHO_PORT);
                header.writeBytes(payload);
                byte[] req = toBytes(header);

                sock.send(new java.net.DatagramPacket(req, req.length,
                        InetAddress.getByName("127.0.0.1"), relayPort));

                byte[] resp = new byte[256];
                java.net.DatagramPacket p = new java.net.DatagramPacket(resp, resp.length);
                sock.receive(p);

                assertTrue(p.getLength() >= 10, "response too short");
                assertEquals(0, resp[0]);
                assertEquals(0, resp[1]);
                assertEquals(0, resp[2]);
                assertEquals(0x01, resp[3]);
                assertEquals(127, resp[4]);
                assertEquals(0, resp[5]);
                assertEquals(0, resp[6]);
                assertEquals(1, resp[7]);
                int respPort = ((resp[8] & 0xFF) << 8) | (resp[9] & 0xFF);
                assertEquals(UDP_ECHO_PORT, respPort);

                byte[] echoed = new byte[p.getLength() - 10];
                System.arraycopy(resp, 10, echoed, 0, echoed.length);
                assertArrayEquals(payload, echoed);

                // Additional UDP rounds
                for (int i = 0; i < 10; i++) {
                    byte[] mPayload = ("udp-msg-" + i).getBytes(StandardCharsets.UTF_8);
                    ByteBuf mHeader = Unpooled.buffer();
                    mHeader.writeZero(3);
                    mHeader.writeByte(0x01);
                    mHeader.writeBytes(new byte[]{127, 0, 0, 1});
                    mHeader.writeShort(UDP_ECHO_PORT);
                    mHeader.writeBytes(mPayload);
                    byte[] mReq = toBytes(mHeader);
                    sock.send(new java.net.DatagramPacket(mReq, mReq.length,
                            InetAddress.getByName("127.0.0.1"), relayPort));

                    byte[] mResp = new byte[256];
                    java.net.DatagramPacket mp = new java.net.DatagramPacket(mResp, mResp.length);
                    sock.receive(mp);
                    byte[] mEchoed = new byte[mp.getLength() - 10];
                    System.arraycopy(mResp, 10, mEchoed, 0, mEchoed.length);
                    assertArrayEquals(mPayload, mEchoed);
                }
            } finally {
                sock.close();
                tcp.close();
            }
        } finally {
            proxy.close();
        }
    }

    @Test
    @SneakyThrows
    @Timeout(value = 60)
    void socks5UdpRelay_chained_e2e() {
        // Scenario: Client -> Proxy A (15284) -> Proxy B (15285) -> UDP Echo Server (UDP_ECHO_PORT)
        int proxyAPort = 15284;
        int proxyBPort = 15285;

        // 1) Started Proxy B (Relay Server)
        SocksConfig configB = new SocksConfig(proxyBPort);
        configB.getWhiteList();
        SocksProxyServer proxyB = new SocksProxyServer(configB, null);

        // 2) Started Proxy A (Client Server) with custom UDP route
        SocksConfig configA = new SocksConfig(proxyAPort);
        configA.getWhiteList();
        SocksProxyServer proxyA = new SocksProxyServer(configA, null);
        
        // Setup Upstream for A to forward to B
        UpstreamSupport supportB = new UpstreamSupport(new AuthenticEndpoint(new InetSocketAddress("127.0.0.1", proxyBPort), null, null), null);
        proxyA.onUdpRoute.replace((s, e) -> {
            UnresolvedEndpoint dstEp = e.getFirstDestination();
            log.info("ProxyA routing UDP to ProxyB for dst: {}", dstEp);
            e.setUpstream(new SocksUdpUpstream(dstEp, configA, supportB));
        });

        try {
            Thread.sleep(1000);

            // 3) Client -> Proxy A: Establish TCP control and request UDP_ASSOCIATE
            Socket tcp = new Socket("127.0.0.1", proxyAPort);
            tcp.setSoTimeout(10000);
            OutputStream tcpOut = tcp.getOutputStream();
            InputStream tcpIn = tcp.getInputStream();

            // Greeting
            tcpOut.write(new byte[]{0x05, 0x01, 0x00});
            tcpOut.flush();
            readExact(tcpIn, 2, 4000);

            // UDP_ASSOCIATE
            tcpOut.write(new byte[]{0x05, 0x03, 0x00, 0x01, 0, 0, 0, 0, 0, 0});
            tcpOut.flush();
            byte[] response = readAtLeast(tcpIn, 10, 32, 4000);
            int relayPortA = ((response[8] & 0xFF) << 8) | (response[9] & 0xFF);
            log.info("Proxy A relay port: {}", relayPortA);

            // 4) Send UDP packet to Proxy A relay port
            DatagramSocket clientSock = new DatagramSocket();
            clientSock.setSoTimeout(10000);
            try {
                byte[] payload = "chained-udp-test".getBytes(StandardCharsets.UTF_8);
                ByteBuf header = Unpooled.buffer();
                header.writeZero(3);
                header.writeByte(0x01); // IPv4
                header.writeBytes(new byte[]{127, 0, 0, 1});
                header.writeShort(UDP_ECHO_PORT);
                header.writeBytes(payload);
                byte[] req = toBytes(header);

                clientSock.send(new java.net.DatagramPacket(req, req.length,
                        InetAddress.getByName("127.0.0.1"), relayPortA));

                // 5) Receive echo response
                byte[] respBuf = new byte[512];
                java.net.DatagramPacket p = new java.net.DatagramPacket(respBuf, respBuf.length);
                clientSock.receive(p);

                assertTrue(p.getLength() >= 10);
                byte[] echoed = new byte[p.getLength() - 10];
                System.arraycopy(respBuf, 10, echoed, 0, echoed.length);
                assertEquals("chained-udp-test", new String(echoed, StandardCharsets.UTF_8));
                log.info("Chained UDP relay success!");
            } finally {
                clientSock.close();
                tcp.close();
            }
        } finally {
            proxyA.close();
            proxyB.close();
        }
    }

    @Test
    @SneakyThrows
    @Timeout(value = 15)
    void socks5UdpRelay_udp2raw_chained_e2e() {
        int proxyAPort = 15288;
        int proxyBPort = 15289;

        // 1) Setup Proxy B (udp2raw server)
        SocksConfig configB = new SocksConfig(proxyBPort);
        configB.setEnableUdp2raw(true);
        configB.setDebug(true);
        SocksProxyServer proxyB = new SocksProxyServer(configB);

        // 2) Setup Proxy A (udp2raw client chained to B)
        SocksConfig configA = new SocksConfig(proxyAPort);
        configA.setEnableUdp2raw(true);
        configA.setDebug(true);
        SocksProxyServer proxyA = new SocksProxyServer(configA);

        UpstreamSupport supportB = new UpstreamSupport(new AuthenticEndpoint(new InetSocketAddress("127.0.0.1", proxyBPort), null, null), null);
        proxyA.onUdpRoute.replace((s, e) -> {
            UnresolvedEndpoint dstEp = e.getFirstDestination();
            log.info("ProxyA routing UDP(udp2raw) to ProxyB for dst {}", dstEp);
            e.setUpstream(new SocksUdpUpstream(dstEp, configA, supportB));
        });

        try {
            Thread.sleep(1000);
            
            // 3) Client: UDP_ASSOCIATE to Proxy A
            DatagramSocket clientSock = new DatagramSocket();
            try (Socket tcp = new Socket("127.0.0.1", proxyAPort)) {
                tcp.setSoTimeout(5000);
                OutputStream out = tcp.getOutputStream();
                InputStream in = tcp.getInputStream();

                // Handshake
                out.write(new byte[]{0x05, 0x01, 0x00});
                out.flush();
                readExact(in, 2, 5000);

                // UDP_ASSOCIATE
                byte[] reqUdp = new byte[]{0x05, 0x03, 0x00, 0x01, 0, 0, 0, 0, 0, 0};
                out.write(reqUdp);
                out.flush();
                byte[] resp = readAtLeast(in, 10, 22, 5000);
                
                int relayPortA = ((resp[resp.length - 2] & 0xFF) << 8) | (resp[resp.length - 1] & 0xFF);
                log.info("ProxyA relay port: {}", relayPortA);

                // 4) Send UDP echo request through Proxy A
                // packet: [header(10)][payload]
                ByteBuf header = Unpooled.buffer();
                header.writeShort(0); // rsv
                header.writeByte(0); // frag
                header.writeByte(0x01); // ipv4
                header.writeBytes(new byte[]{127, 0, 0, 1});
                header.writeShort(UDP_ECHO_PORT);
                byte[] payload = "udp2raw-chained-test".getBytes(StandardCharsets.UTF_8);
                header.writeBytes(payload);
                byte[] req = toBytes(header);

                clientSock.send(new java.net.DatagramPacket(req, req.length,
                        InetAddress.getByName("127.0.0.1"), relayPortA));

                // 5) Receive echo response
                byte[] respBuf = new byte[1024];
                java.net.DatagramPacket p = new java.net.DatagramPacket(respBuf, respBuf.length);
                clientSock.setSoTimeout(8000);
                clientSock.receive(p);

                assertTrue(p.getLength() >= 10);
                byte[] echoed = new byte[p.getLength() - 10];
                System.arraycopy(respBuf, 10, echoed, 0, echoed.length);
                assertEquals("udp2raw-chained-test", new String(echoed, StandardCharsets.UTF_8));
                log.info("UDP2RAW Chained UDP relay success!");
            } finally {
                clientSock.close();
            }
        } finally {
            proxyA.close();
            proxyB.close();
        }
    }

    @Test
    @SneakyThrows
    @Timeout(value = 15)
    void socks5AnonymousLogin_e2e() {
        int proxyPort = 15282;
        SocksConfig config = new SocksConfig(proxyPort);
        config.getWhiteList(); // Trigger lazy init in calling thread
        SocksProxyServer proxy = new SocksProxyServer(config, null);

        try {
            Thread.sleep(1000);
            try (Socket s = new Socket("127.0.0.1", proxyPort)) {
                s.setSoTimeout(4000);
                OutputStream out = s.getOutputStream();
                InputStream in = s.getInputStream();

                // 1) Greeting: No auth (0x00)
                log.info("Anon Client sending greeting...");
                out.write(new byte[]{0x05, 0x01, 0x00});
                out.flush();
                byte[] hs = readExact(in, 2, 8000);
                assertArrayEquals(new byte[]{0x05, 0x00}, hs);
                log.info("Anon Client received handshake response");

                // 2) Connect to tcp echo server
                out.write(buildSocks5ConnectReqIpv4("127.0.0.1", TCP_ECHO_PORT));
                out.flush();
                byte[] conn = readAtLeast(in, 4, 10, 3000);
                assertEquals(0x05, conn[0] & 0xFF);
                assertEquals(0x00, conn[1] & 0xFF);

                // 3) Payload
                String message = "anonymous-tcp-e2e";
                out.write(message.getBytes(StandardCharsets.UTF_8));
                out.flush();
                byte[] back = readExact(in, message.getBytes(StandardCharsets.UTF_8).length, 5000);
                assertEquals(message, new String(back, StandardCharsets.UTF_8));

                // 4) Additional rounds
                for (int i = 0; i < 10; i++) {
                    String loopMsg = "loop-msg-anon-" + i;
                    out.write(loopMsg.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    byte[] loopBack = readExact(in, loopMsg.getBytes(StandardCharsets.UTF_8).length, 5000);
                    assertEquals(loopMsg, new String(loopBack, StandardCharsets.UTF_8));
                }
            }
        } finally {
            proxy.close();
        }
    }

    @Test
    @SneakyThrows
    @Timeout(value = 15)
    void socks5TrafficShaping_e2e() {
        int proxyPort = 15283;
        SocksConfig config = new SocksConfig(proxyPort);
        config.getWhiteList(); // Trigger lazy init in calling thread
        config.setTrafficShapingInterval(100); 
        SocksUser usr = new SocksUser("u_shaping");
        usr.setPassword("p_shaping");
        usr.setIpLimit(-1);

        DefaultSocksAuthenticator authenticator = new DefaultSocksAuthenticator(Collections.singletonList(usr));
        SocksProxyServer proxy = new SocksProxyServer(config, authenticator);

        try {
            Thread.sleep(1000);
            byte[] payload;
            try (Socket s = new Socket("127.0.0.1", proxyPort)) {
                s.setSoTimeout(4000);
                OutputStream out = s.getOutputStream();
                InputStream in = s.getInputStream();

                // Handshake
                out.write(new byte[]{0x05, 0x01, 0x02});
                out.flush();
                readExact(in, 2, 5000);

                // Auth
                byte[] u = "u_shaping".getBytes();
                byte[] p = "p_shaping".getBytes();
                ByteBuf auth = Unpooled.buffer();
                auth.writeByte(0x01);
                auth.writeByte(u.length);
                auth.writeBytes(u);
                auth.writeByte(p.length);
                auth.writeBytes(p);
                out.write(toBytes(auth));
                out.flush();
                readExact(in, 2, 5000);

                // Connect
                out.write(buildSocks5ConnectReqIpv4("127.0.0.1", TCP_ECHO_PORT));
                out.flush();
                readAtLeast(in, 4, 10, 3000);

                // Send 10KB payload
                payload = new byte[1024 * 10];
                for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i % 256);
                out.write(payload);
                out.flush();

                // Receive 10KB echo
                byte[] back = new byte[payload.length];
                int totalRead = 0;
                while (totalRead < back.length) {
                    int n = in.read(back, totalRead, back.length - totalRead);
                    if (n == -1) break;
                    totalRead += n;
                }
                assertEquals(payload.length, totalRead);
            }

            Thread.sleep(500);

            SocksUser user = authenticator.getStore().get("u_shaping");
            assertNotNull(user);
            assertTrue(user.getTotalReadBytes() >= payload.length);
            assertTrue(user.getTotalWriteBytes() >= payload.length);
        } finally {
            proxy.close();
        }
    }

    static byte[] buildSocks5ConnectReqIpv4(String host, int port) {
        byte[] req = new byte[10];
        req[0] = 0x05;
        req[1] = 0x01;
        req[2] = 0x00;
        req[3] = 0x01;
        req[4] = 127; req[5] = 0; req[6] = 0; req[7] = 1;
        req[8] = (byte) ((port >> 8) & 0xFF);
        req[9] = (byte) (port & 0xFF);
        return req;
    }

    static byte[] readExact(InputStream in, int len, int timeoutMs) throws Exception {
        byte[] buf = new byte[len];
        int read = 0;
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (read < len && System.currentTimeMillis() < deadline) {
            int n = in.read(buf, read, len - read);
            if (n == -1) break;
            read += n;
        }
        assertEquals(len, read, "short read after " + (System.currentTimeMillis() - (deadline - timeoutMs)) + "ms");
        return buf;
    }

    static byte[] readAtLeast(InputStream in, int minLen, int maxLen, int timeoutMs) throws Exception {
        byte[] buf = new byte[maxLen];
        int read = 0;
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (read < minLen && System.currentTimeMillis() < deadline) {
            int n = in.read(buf, read, maxLen - read);
            if (n == -1) break;
            read += n;
        }
        assertTrue(read >= minLen, "short read");
        byte[] out = new byte[read];
        System.arraycopy(buf, 0, out, 0, read);
        return out;
    }

    static byte[] toBytes(ByteBuf buf) {
        byte[] b = new byte[buf.readableBytes()];
        buf.readBytes(b);
        buf.release();
        return b;
    }
}
