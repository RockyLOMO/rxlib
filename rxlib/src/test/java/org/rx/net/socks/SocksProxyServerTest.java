package org.rx.net.socks;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.rx.net.Sockets;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Slf4j
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SocksProxyServerTest {

    static final int ECHO_PORT = 15199;
    static Channel echoServerChannel;
    static ServerBootstrap echoBootstrap;

    @BeforeAll
    static void setup() throws InterruptedException {
        echoBootstrap = Sockets.serverBootstrap(ch -> {
            ch.pipeline().addLast(new EchoHandler());
        });
        echoServerChannel = echoBootstrap.bind(Sockets.newAnyEndpoint(ECHO_PORT)).sync().channel();
        log.info("Echo server started on port {}", ECHO_PORT);
        Thread.sleep(500);
    }

    @AfterAll
    static void teardown() {
        if (echoServerChannel != null) echoServerChannel.close();
        if (echoBootstrap != null) Sockets.closeBootstrap(echoBootstrap);
    }

    @Test
    @Order(1)
    @SneakyThrows
    void testNormalMode() {
        int proxyPort = 15180;
        SocksConfig config = new SocksConfig();
        config.setListenPort(proxyPort);
        SocksProxyServer proxyServer = new SocksProxyServer(config, null);

        try {
            Thread.sleep(500); // Wait for bind
            assertTrue(proxyServer.isBind(), "Normal mode proxy should be bound");

            runSocks5ClientTest(proxyPort, "Normal Mode Protocol Test");
        } finally {
            proxyServer.close();
        }
    }

    @Test
    @Order(2)
    @SneakyThrows
    void testMemoryMode() {
        int frontendPort = 15181;
        
        // Custom frontend TCP server that acts as a container for memory-mode SocksProxyServer
        ServerBootstrap memoryFrontendBootstrap = Sockets.serverBootstrap(ch -> {
            SocksConfig config = new SocksConfig();
            config.setListenPort(0); // random port for UDP to avoid BindException on multiple connections
            
            // Create in memory mode
            new SocksProxyServer(config, null, ch);
        });
        Channel frontendChannel = memoryFrontendBootstrap.bind(Sockets.newAnyEndpoint(frontendPort)).sync().channel();

        try {
            runSocks5ClientTest(frontendPort, "Memory Mode Protocol Test");
        } finally {
            frontendChannel.close();
            Sockets.closeBootstrap(memoryFrontendBootstrap);
        }
    }

    @SneakyThrows
    private void runSocks5ClientTest(int proxyPort, String message) {
        AtomicReference<String> received = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        try (Socket s = new Socket("127.0.0.1", proxyPort)) {
            s.setSoTimeout(5000);
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();

            // Step 1: Handshake
            out.write(new byte[]{0x05, 0x01, 0x00});
            out.flush();
            byte[] hsResp = new byte[2];
            assertEquals(2, readFully(in, hsResp, 3000));
            assertEquals(0x05, hsResp[0] & 0xFF);
            assertEquals(0x00, hsResp[1] & 0xFF);

            // Step 2: CONNECT
            byte[] connectReq = buildSocks5ConnectReq("127.0.0.1", ECHO_PORT);
            out.write(connectReq);
            out.flush();

            byte[] connResp = new byte[10];
            int n = readFully(in, connResp, 3000);
            assertTrue(n >= 4);
            assertEquals(0x00, connResp[1] & 0xFF, "CONNECT failed");

            // Step 3: Payload
            byte[] payload = message.getBytes(StandardCharsets.UTF_8);
            out.write(payload);
            out.flush();

            // Step 4: Receive ECHO
            new Thread(() -> {
                try {
                    byte[] buf = new byte[1024];
                    int nr = in.read(buf, 0, buf.length);
                    if (nr > 0) {
                        received.set(new String(buf, 0, nr, StandardCharsets.UTF_8));
                    }
                } catch (Exception e) {
                    log.debug("read error: {}", e.getMessage());
                }
                latch.countDown();
            }).start();

            boolean ok = latch.await(5, TimeUnit.SECONDS);
            assertTrue(ok, "Timeout waiting for echo response");
            assertEquals(message, received.get(), "Echoed message doesn't match");
        }
    }

    static byte[] buildSocks5ConnectReq(String host, int port) {
        byte[] hostBytes = new byte[]{127, 0, 0, 1}; // hardcode for test fast
        byte[] req = new byte[10];
        req[0] = 0x05;
        req[1] = 0x01;
        req[2] = 0x00;
        req[3] = 0x01;
        req[4] = hostBytes[0];
        req[5] = hostBytes[1];
        req[6] = hostBytes[2];
        req[7] = hostBytes[3];
        req[8] = (byte) ((port >> 8) & 0xFF);
        req[9] = (byte) (port & 0xFF);
        return req;
    }

    static int readFully(InputStream in, byte[] buf, int timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        int total = 0;
        while (total < buf.length && System.currentTimeMillis() < deadline) {
            try {
                int n = in.read(buf, total, buf.length - total);
                if (n == -1) break;
                total += n;
            } catch (SocketTimeoutException e) {
                break;
            }
        }
        return total;
    }

    @ChannelHandler.Sharable
    static class EchoHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            ctx.writeAndFlush(msg);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }
}
