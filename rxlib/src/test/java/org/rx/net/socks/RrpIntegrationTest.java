package org.rx.net.socks;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.local.LocalAddress;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.rx.io.Serializer;
import org.rx.net.Sockets;
import org.rx.net.support.EndpointTracer;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.rx.net.socks.RrpConfig.ATTR_SVR;

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

    // Validation tests from RrpValidationTest
    @Test
    void serverRejectsOversizedTokenLenAndReleases() {
        RrpConfig conf = new RrpConfig();
        conf.setToken("t");
        conf.setBindPort(0);
        RrpServer server = new RrpServer(conf);
        try {
            EmbeddedChannel ch = new EmbeddedChannel();
            ch.attr(ATTR_SVR).set(server);
            ch.pipeline().addLast(RrpServer.ServerHandler.DEFAULT);
            ch.pipeline().fireChannelActive();

            ByteBuf buf = Unpooled.buffer();
            buf.writeByte(RrpConfig.ACTION_REGISTER);
            buf.writeInt(RrpServer.MAX_TOKEN_LEN + 1);
            // token bytes omitted on purpose

            ch.writeInbound(buf);
            assertEquals(0, buf.refCnt(), "msg should be released by handler");

            ch.runPendingTasks();
            assertFalse(ch.isOpen(), "channel should be closed on invalid tokenLen");
        } finally {
            server.close();
        }
    }

    @Test
    void serverRejectsOversizedRegisterPayloadLenAndReleases() {
        RrpConfig conf = new RrpConfig();
        conf.setToken("t");
        conf.setBindPort(0);
        RrpServer server = new RrpServer(conf);
        try {
            EmbeddedChannel ch = new EmbeddedChannel();
            ch.attr(ATTR_SVR).set(server);
            ch.pipeline().addLast(RrpServer.ServerHandler.DEFAULT);
            ch.pipeline().fireChannelActive();

            byte[] token = "t".getBytes(StandardCharsets.US_ASCII);
            ByteBuf buf = Unpooled.buffer();
            buf.writeByte(RrpConfig.ACTION_REGISTER);
            buf.writeInt(token.length);
            buf.writeBytes(token);
            buf.writeInt(RrpServer.MAX_REGISTER_BYTES + 1);
            // payload omitted on purpose

            ch.writeInbound(buf);
            assertEquals(0, buf.refCnt(), "msg should be released by handler");

            ch.runPendingTasks();
            assertFalse(ch.isOpen(), "channel should be closed on invalid register len");
        } finally {
            server.close();
        }
    }

    @Test
    void clientRejectsInvalidIdLenAndReleases() {
        RrpConfig conf = new RrpConfig();
        conf.setProxies(Collections.emptyList());
        RrpClient client = new RrpClient(conf);
        RrpClient.ClientHandler h = client.new ClientHandler();

        EmbeddedChannel ch = new EmbeddedChannel(h);
        ByteBuf buf = Unpooled.buffer();
        buf.writeByte(RrpConfig.ACTION_FORWARD);
        buf.writeInt(2090);
        buf.writeInt(RrpServer.MAX_CHANNEL_ID_LEN + 1);
        // id bytes omitted

        ch.writeInbound(buf);
        assertEquals(0, buf.refCnt(), "msg should be released by handler");

        ch.runPendingTasks();
        assertFalse(ch.isOpen(), "server channel should be closed on invalid idLen");
    }

    @Test
    void clientIgnoresUnknownRemotePortButStillReleases() {
        RrpConfig conf = new RrpConfig();
        conf.setProxies(Collections.emptyList());
        RrpClient client = new RrpClient(conf);
        RrpClient.ClientHandler h = client.new ClientHandler();

        EmbeddedChannel ch = new EmbeddedChannel(h);
        ByteBuf buf = Unpooled.buffer();
        buf.writeByte(RrpConfig.ACTION_FORWARD);
        buf.writeInt(2090);
        byte[] id = "abc".getBytes(StandardCharsets.US_ASCII);
        buf.writeInt(id.length);
        buf.writeBytes(id);
        buf.writeBytes(new byte[]{1, 2, 3}); // payload

        ch.writeInbound(buf);
        assertEquals(0, buf.refCnt(), "msg should be released by handler");
        assertTrue(ch.isOpen(), "channel should remain open for unknown remotePort");
    }

    @Test
    void clientIgnoresHeartbeatAndReleases() {
        RrpConfig conf = new RrpConfig();
        conf.setEnableReconnect(false);
        conf.setProxies(Collections.emptyList());
        RrpClient client = new RrpClient(conf);
        RrpClient.ClientHandler h = client.new ClientHandler();

        EmbeddedChannel ch = new EmbeddedChannel(h);
        releaseOutbound(ch);
        ByteBuf buf = Unpooled.buffer(1);
        buf.writeByte(RrpConfig.ACTION_HEARTBEAT);

        ch.writeInbound(buf);
        assertEquals(0, buf.refCnt(), "heartbeat should be released by handler");
        assertTrue(ch.isOpen(), "heartbeat must not close client channel");
        Object out = ch.readOutbound();
        if (out != null) {
            io.netty.util.ReferenceCountUtil.release(out);
        }
        assertNull(out, "client should not reply to heartbeat");
    }

    @Test
    void serverRepliesHeartbeatAndReleases() {
        RrpConfig conf = new RrpConfig();
        conf.setBindPort(0);
        RrpServer server = new RrpServer(conf);
        try {
            EmbeddedChannel ch = new EmbeddedChannel();
            ch.attr(ATTR_SVR).set(server);
            ch.pipeline().addLast(RrpServer.ServerHandler.DEFAULT);
            ch.pipeline().fireChannelActive();

            ByteBuf buf = Unpooled.buffer(1);
            buf.writeByte(RrpConfig.ACTION_HEARTBEAT);

            ch.writeInbound(buf);
            assertEquals(0, buf.refCnt(), "heartbeat should be released by handler");

            ByteBuf out = ch.readOutbound();
            assertNotNull(out, "server should reply heartbeat");
            try {
                assertEquals(RrpConfig.ACTION_HEARTBEAT, out.readByte());
            } finally {
                out.release();
            }
        } finally {
            server.close();
        }
    }

    @Test
    void clientInactiveClearsStaleProxyContexts() {
        RrpConfig conf = new RrpConfig();
        conf.setEnableReconnect(false);
        conf.setProxies(Collections.singletonList(newProxy("stale", 19101, "u1:p1")));
        RrpClient client = new RrpClient(conf);
        EmbeddedChannel serverChannel = new EmbeddedChannel();
        EmbeddedChannel localChannel = new EmbeddedChannel();
        try {
            RrpClient.RpClientProxy proxy = new RrpClient.RpClientProxy(conf.getProxies().get(0), serverChannel);
            proxy.localChannels.put("old", localChannel);
            client.proxyMap.put(proxy.p.getRemotePort(), proxy);

            EmbeddedChannel ch = new EmbeddedChannel(client.new ClientHandler());
            releaseOutbound(ch);
            ch.pipeline().fireChannelInactive();

            assertTrue(client.proxyMap.isEmpty(), "main channel inactive should drop stale proxy contexts");
            assertTrue(proxy.localChannels.isEmpty(), "stale local channel map should be cleared");
        } finally {
            client.close();
            serverChannel.close();
            localChannel.close();
        }
    }

    @Test
    void serverRetryRegisterAfterBindFailureShouldSucceed() throws Exception {
        RrpConfig conf = new RrpConfig();
        conf.setToken("t");
        conf.setBindPort(0);
        RrpServer server = new RrpServer(conf);
        try (ServerSocket blocker = new ServerSocket(0)) {
            int remotePort = blocker.getLocalPort();
            EmbeddedChannel ch = new EmbeddedChannel();
            ch.attr(ATTR_SVR).set(server);
            ch.pipeline().addLast(RrpServer.ServerHandler.DEFAULT);
            ch.pipeline().fireChannelActive();

            writeRegister(ch, "t", newProxy("retry", remotePort, "u1:p1"));
            RrpServer.RpClient rpClient = server.clients.get(ch);
            waitFor(() -> rpClient != null && !rpClient.proxyMap.containsKey(remotePort), 5000, "failed bind should be cleaned up");

            blocker.close();

            writeRegister(ch, "t", newProxy("retry", remotePort, "u1:p1"));
            waitFor(() -> {
                RrpServer.RpClientProxy proxy = rpClient.proxyMap.get(remotePort);
                return proxy != null && proxy.remoteServerChannel != null && proxy.remoteServerChannel.isActive();
            }, 5000, "retry register should bind after cleanup");
        } finally {
            server.close();
        }
    }

    @Test
    void remoteRelayBufferQueuesUntilChannelWritable() {
        EmbeddedChannel remoteChannel = new EmbeddedChannel();
        RrpServer.RemoteRelayBuffer relayBuffer = new RrpServer.RemoteRelayBuffer();
        remoteChannel.attr(RrpServer.ATTR_REMOTE_RELAY_BUF).set(relayBuffer);
        remoteChannel.unsafe().outboundBuffer().setUserDefinedWritability(1, false);

        ByteBuf payload = Unpooled.wrappedBuffer("relay-data".getBytes(StandardCharsets.US_ASCII));
        assertTrue(relayBuffer.offer(remoteChannel, payload));
        remoteChannel.runPendingTasks();

        assertNull(remoteChannel.readOutbound(), "payload should stay queued while channel is not writable");
        assertEquals("relay-data".getBytes(StandardCharsets.US_ASCII).length, relayBuffer.pendingBytes.get());

        remoteChannel.unsafe().outboundBuffer().setUserDefinedWritability(1, true);
        relayBuffer.scheduleDrain(remoteChannel);
        remoteChannel.runPendingTasks();

        ByteBuf flushed = remoteChannel.readOutbound();
        assertNotNull(flushed);
        assertEquals("relay-data", flushed.toString(StandardCharsets.US_ASCII));
        flushed.release();
        assertEquals(0, relayBuffer.pendingBytes.get());
    }

    @Test
    void remoteRelayBufferClosesSlowChannelWhenQueuedBytesOverflow() {
        EmbeddedChannel remoteChannel = new EmbeddedChannel();
        RrpServer.RemoteRelayBuffer relayBuffer = new RrpServer.RemoteRelayBuffer();
        remoteChannel.attr(RrpServer.ATTR_REMOTE_RELAY_BUF).set(relayBuffer);
        remoteChannel.unsafe().outboundBuffer().setUserDefinedWritability(1, false);

        ByteBuf payload = Unpooled.buffer(RrpServer.MAX_PENDING_FORWARD_BYTES + 1);
        payload.writeZero(RrpServer.MAX_PENDING_FORWARD_BYTES + 1);

        assertFalse(relayBuffer.offer(remoteChannel, payload));
        remoteChannel.runPendingTasks();

        assertFalse(remoteChannel.isOpen(), "slow remote channel should be closed once queue cap is exceeded");
        assertEquals(0, relayBuffer.pendingBytes.get());
        assertNull(remoteChannel.readOutbound());
    }

    @Test
    void endpointTracerIgnoresLocalAddressChannels() {
        EmbeddedChannel localChannel = new EmbeddedChannel();
        localChannel.connect(new LocalAddress("rrp-local"));
        assertEquals(Sockets.newAnyEndpoint(0), EndpointTracer.TCP.head(localChannel));
        localChannel.close();
    }

    // Helper methods
    static void writeRegister(EmbeddedChannel ch, String token, RrpConfig.Proxy proxy) {
        ByteBuf buf = Unpooled.buffer();
        buf.writeByte(RrpConfig.ACTION_REGISTER);
        byte[] tokenBytes = token.getBytes(StandardCharsets.US_ASCII);
        buf.writeInt(tokenBytes.length);
        buf.writeBytes(tokenBytes);
        byte[] data = Serializer.DEFAULT.serializeToBytes(Collections.singletonList(proxy));
        buf.writeInt(data.length);
        buf.writeBytes(data);
        ch.writeInbound(buf);
        assertEquals(0, buf.refCnt(), "msg should be released by handler");
    }

    static RrpConfig.Proxy newProxy(String name, int remotePort, String auth) {
        RrpConfig.Proxy proxy = new RrpConfig.Proxy();
        proxy.setName(name);
        proxy.setRemotePort(remotePort);
        proxy.setAuth(auth);
        return proxy;
    }

    static void waitFor(BooleanSupplier condition, long timeoutMillis, String message) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        fail(message);
    }

    static void releaseOutbound(EmbeddedChannel ch) {
        Object msg;
        while ((msg = ch.readOutbound()) != null) {
            io.netty.util.ReferenceCountUtil.release(msg);
        }
    }
}

