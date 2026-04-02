package org.rx.net.socks;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.rx.net.Sockets;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

@Slf4j
class RrpIntegrationTest {
    static final int TCP_ECHO_PORT = 15499;
    static ServerBootstrap tcpEchoBootstrap;
    static Channel tcpEchoChannel;

    @BeforeAll
    static void setup() throws Exception {
        tcpEchoBootstrap = Sockets.serverBootstrap(ch -> ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                ctx.writeAndFlush(msg);
            }
        }));
        tcpEchoChannel = tcpEchoBootstrap.bind(Sockets.newAnyEndpoint(TCP_ECHO_PORT)).sync().channel();
        Thread.sleep(300);
    }

    @AfterAll
    static void teardown() {
        if (tcpEchoChannel != null) tcpEchoChannel.close();
        if (tcpEchoBootstrap != null) Sockets.closeBootstrap(tcpEchoBootstrap);
    }

    @Test
    @SneakyThrows
    @Timeout(value = 25)
    void rrpRemotePort_socks5PasswordAuth_connectToLocalEcho_e2e() {
        int bindPort = 19000;
        int remotePort = 19001;

        RrpConfig sConf = new RrpConfig();
        sConf.setToken("tok1");
        sConf.setBindPort(bindPort);
        RrpServer server = new RrpServer(sConf);

        RrpConfig cConf = new RrpConfig();
        cConf.setToken("tok1");
        cConf.setServerEndpoint("127.0.0.1:" + bindPort);
        RrpConfig.Proxy p = new RrpConfig.Proxy();
        p.setName("it");
        p.setRemotePort(remotePort);
        p.setAuth("u1:p1");
        cConf.setProxies(Collections.singletonList(p));
        RrpClient client = new RrpClient(cConf);

        try {
            client.connectAsync().get(8, java.util.concurrent.TimeUnit.SECONDS);

            // Wait for remotePort to be bound by server (REGISTER is async after connect).
            waitForPortOpen("127.0.0.1", remotePort, 8000);

            try (Socket s = new Socket("127.0.0.1", remotePort)) {
                s.setSoTimeout(5000);
                OutputStream out = s.getOutputStream();
                InputStream in = s.getInputStream();

                // 1) greeting: request username/password auth
                out.write(new byte[]{0x05, 0x01, 0x02});
                out.flush();
                byte[] hs = readExact(in, 2, 3000);
                assertArrayEquals(new byte[]{0x05, 0x02}, hs);

                // 2) auth subnegotiation (u1/p1)
                byte[] u = "u1".getBytes(StandardCharsets.US_ASCII);
                byte[] pw = "p1".getBytes(StandardCharsets.US_ASCII);
                out.write(new byte[]{
                        0x01,
                        (byte) u.length
                });
                out.write(u);
                out.write(new byte[]{
                        (byte) pw.length
                });
                out.write(pw);
                out.flush();
                byte[] authResp = readExact(in, 2, 3000);
                assertArrayEquals(new byte[]{0x01, 0x00}, authResp);

                // 3) CONNECT to local echo server (client side)
                out.write(buildSocks5ConnectReqIpv4("127.0.0.1", TCP_ECHO_PORT));
                out.flush();
                byte[] conn = readAtLeast(in, 4, 10, 3000);
                assertEquals(0x05, conn[0] & 0xFF);
                assertEquals(0x00, conn[1] & 0xFF);

                // 4) payload roundtrip
                String message = "rrp-e2e";
                out.write(message.getBytes(StandardCharsets.UTF_8));
                out.flush();
                byte[] back = readExact(in, message.getBytes(StandardCharsets.UTF_8).length, 3000);
                assertEquals(message, new String(back, StandardCharsets.UTF_8));
            }
        } finally {
            client.close();
            server.close();
        }
    }

    static void waitForPortOpen(String host, int port, int timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(host, port), 300);
                return;
            } catch (Exception ignore) {
                Thread.sleep(100);
            }
        }
        fail("port not open: " + host + ":" + port);
    }

    static byte[] buildSocks5ConnectReqIpv4(String host, int port) {
        byte[] req = new byte[10];
        req[0] = 0x05;
        req[1] = 0x01; // CONNECT
        req[2] = 0x00;
        req[3] = 0x01; // IPv4
        req[4] = 127;
        req[5] = 0;
        req[6] = 0;
        req[7] = 1;
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
        assertEquals(len, read, "short read");
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
}

