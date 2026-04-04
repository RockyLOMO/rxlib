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
import org.rx.net.Sockets;

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
        int proxyPort = 15281;
        SocksConfig config = new SocksConfig(proxyPort);
        config.getWhiteList(); // Trigger lazy init in calling thread
        SocksProxyServer proxy = new SocksProxyServer(config, null);

        try {
            Thread.sleep(1000);
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

                sock.send(new java.net.DatagramPacket(req, req.length, InetAddress.getByName("127.0.0.1"), proxyPort));

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
                int port = ((resp[8] & 0xFF) << 8) | (resp[9] & 0xFF);
                assertEquals(UDP_ECHO_PORT, port);

                byte[] echoed = new byte[p.getLength() - 10];
                System.arraycopy(resp, 10, echoed, 0, echoed.length);
                assertArrayEquals(payload, echoed);

                // 3) Additional UDP rounds
                for (int i = 0; i < 20; i++) {
                    byte[] mPayload = ("udp-msg-" + i).getBytes(StandardCharsets.UTF_8);
                    ByteBuf mHeader = Unpooled.buffer();
                    mHeader.writeZero(3);
                    mHeader.writeByte(0x01);
                    mHeader.writeBytes(new byte[]{127, 0, 0, 1});
                    mHeader.writeShort(UDP_ECHO_PORT);
                    mHeader.writeBytes(mPayload);
                    byte[] mReq = toBytes(mHeader);
                    sock.send(new java.net.DatagramPacket(mReq, mReq.length, InetAddress.getByName("127.0.0.1"), proxyPort));

                    byte[] mResp = new byte[256];
                    java.net.DatagramPacket mp = new java.net.DatagramPacket(mResp, mResp.length);
                    sock.receive(mp);
                    byte[] mEchoed = new byte[mp.getLength() - 10];
                    System.arraycopy(mResp, 10, mEchoed, 0, mEchoed.length);
                    assertArrayEquals(mPayload, mEchoed);
                }
            } finally {
                sock.close();
            }
        } finally {
            proxy.close();
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
