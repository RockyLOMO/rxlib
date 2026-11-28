package org.rx.net.socks;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.local.LocalAddress;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.rx.io.FurySerializer;
import org.rx.io.JdkAndJsonSerializer;
import org.rx.net.EgressCodec;
import org.rx.net.EgressConfig;
import org.rx.net.Sockets;
import org.rx.net.support.EndpointTracer;
import org.rx.net.transport.core.TransportSession;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

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
        Thread.sleep(200);
    }

    @AfterAll
    static void teardown() {
        if (tcpEchoChannel != null) {
            tcpEchoChannel.close();
        }
        if (tcpEchoBootstrap != null) {
            Sockets.closeBootstrap(tcpEchoBootstrap);
        }
    }

    static final class EmbeddedTransportSession implements TransportSession {
        final long id;
        final EmbeddedChannel channel;
        volatile boolean connected = true;

        EmbeddedTransportSession(long id, EmbeddedChannel channel) {
            this.id = id;
            this.channel = channel;
        }

        @Override public long sessionId() { return id; }
        @Override public String localPeerId() { return "local"; }
        @Override public String remotePeerId() { return "remote"; }
        @Override public boolean isConnected() { return connected && channel.isOpen(); }
        @Override public boolean isWritable() { return isConnected() && channel.isWritable(); }
        @Override public boolean isUsingUdp() { return false; }
        @Override public boolean isAdaptiveUdpNegotiated() { return false; }
        @Override public boolean isEncrypted() { return true; }
        @Override public InetSocketAddress remoteEndpoint() { return Sockets.newLoopbackEndpoint(12345); }
        @Override public InetSocketAddress localEndpoint() { return Sockets.newLoopbackEndpoint(54321); }
        @Override public long lastHeartbeatMillis() { return 0; }
        @Override public long heartbeatRttMillis() { return 0; }
        @Override public Channel channel() { return channel; }
        @Override public void send(ByteBuf payload) { channel.writeAndFlush(payload); }
        @Override public ChannelFuture sendAsync(ByteBuf payload) { return channel.writeAndFlush(payload); }
        @Override public ChannelFuture shutdownOutput() { return channel.close(); }
        @Override public void setReceiveHandler(BiConsumer<TransportSession, ByteBuf> handler) {}
        @Override public <T> boolean hasAttr(AttributeKey<T> key) { return channel.hasAttr(key); }
        @Override public <T> T attr(AttributeKey<T> key) { return channel.attr(key).get(); }
        @Override public <T> void attr(AttributeKey<T> key, T val) { channel.attr(key).set(val); }
        @Override public void close() { connected = false; channel.close(); }
    }

    @Test
    @SneakyThrows
    @Timeout(value = 25)
    void rrpRemotePort_socks5PasswordAuth_connectToLocalEcho_e2e_acrossAutoEgressModes() {
        for (EgressCodec clientCodec : new EgressCodec[]{EgressCodec.HTTP, EgressCodec.ZLIB_STORED, EgressCodec.NONE}) {
            int bindPort, remotePort;
            try (ServerSocket control = new ServerSocket(0); ServerSocket relay = new ServerSocket(0)) {
                bindPort = control.getLocalPort();
                remotePort = relay.getLocalPort();
            }

            RrpConfig sConf = new RrpConfig();
            sConf.setToken("tok1");
            sConf.setBindPort(bindPort);
            // Server defaults to EgressConfig.serverAutoAll() accepting NONE / ZLIB_STORED / HTTP
            RrpServer server = new RrpServer(sConf);

            RrpConfig cConf = new RrpConfig();
            cConf.setToken("tok1");
            cConf.setServerEndpoint("127.0.0.1:" + bindPort);
            cConf.setEgressConfig(EgressConfig.client(clientCodec));
            RrpConfig.Proxy p = new RrpConfig.Proxy();
            p.setName("it-" + clientCodec.name());
            p.setRemotePort(remotePort);
            p.setAuth("u1:p1");
            cConf.setProxies(Collections.singletonList(p));
            RrpClient client = new RrpClient(cConf);

            try {
                client.connectAsync().get(8, TimeUnit.SECONDS);
                assertTrue(client.session().isEncrypted(), "RRP v2 with token must negotiate AEAD encryption");
                assertEquals(clientCodec, client.session().egressCodec());

                waitForPortOpen("127.0.0.1", remotePort, 8000);

                try (Socket s = new Socket("127.0.0.1", remotePort)) {
                    s.setSoTimeout(5000);
                    OutputStream out = s.getOutputStream();
                    InputStream in = s.getInputStream();

                    // 1) greeting: username/password auth
                    out.write(new byte[]{0x05, 0x01, 0x02});
                    out.flush();
                    assertArrayEquals(new byte[]{0x05, 0x02}, readExact(in, 2, 3000));

                    // 2) auth subnegotiation (u1/p1)
                    byte[] u = "u1".getBytes(StandardCharsets.US_ASCII);
                    byte[] pw = "p1".getBytes(StandardCharsets.US_ASCII);
                    out.write(new byte[]{0x01, (byte) u.length});
                    out.write(u);
                    out.write(new byte[]{(byte) pw.length});
                    out.write(pw);
                    out.flush();
                    assertArrayEquals(new byte[]{0x01, 0x00}, readExact(in, 2, 3000));

                    // 3) CONNECT to local echo server
                    out.write(buildSocks5ConnectReqIpv4("127.0.0.1", TCP_ECHO_PORT));
                    out.flush();
                    byte[] conn = readAtLeast(in, 4, 10, 3000);
                    assertEquals(0x05, conn[0] & 0xFF);
                    assertEquals(0x00, conn[1] & 0xFF);

                    // 4) payload roundtrip
                    String message = "rrp-e2e-" + clientCodec.name();
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
    }

    @Test
    void configToStringExcludesSensitiveCredentials() {
        RrpConfig conf = new RrpConfig();
        conf.setToken("super-secret-token");
        conf.setNodeSecret("super-secret-node-key");
        RrpConfig.Proxy p = new RrpConfig.Proxy();
        p.setName("p1");
        p.setRemotePort(12345);
        p.setAuth("alice:topsecretpass");
        conf.setProxies(Collections.singletonList(p));

        String s = conf.toString();
        assertFalse(s.contains("super-secret-token"));
        assertFalse(s.contains("super-secret-node-key"));
        assertFalse(s.contains("topsecretpass"));
    }

    @Test
    @Timeout(10)
    void connectFailureCompletesFutureWhenReconnectDisabled() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }

        RrpConfig conf = new RrpConfig();
        conf.setToken("tok-fail");
        conf.setServerEndpoint("127.0.0.1:" + port);
        conf.setEnableReconnect(false);
        conf.setProxies(Collections.emptyList());
        RrpClient client = new RrpClient(conf);
        try {
            Future<Void> future = client.connectAsync();
            ExecutionException ex = assertThrows(ExecutionException.class, () -> future.get(3, TimeUnit.SECONDS));
            assertNotNull(ex.getCause());
            assertTrue(future.isDone());
        } finally {
            client.close();
        }
    }

    @Test
    void serverRejectsOversizedTokenLen() {
        RrpConfig conf = new RrpConfig();
        conf.setToken("t");
        conf.setBindPort(0);
        RrpServer server = new RrpServer(conf);
        EmbeddedChannel ch = new EmbeddedChannel();
        try {
            EmbeddedTransportSession session = new EmbeddedTransportSession(1L, ch);
            RrpServer.RpClient rpClient = new RrpServer.RpClient(server, session);
            server.clientsBySessionId.put(1L, rpClient);

            ByteBuf buf = Unpooled.buffer();
            buf.writeByte(RrpConfig.ACTION_REGISTER);
            buf.writeInt(RrpServer.MAX_TOKEN_LEN + 1);
            try {
                server.handleInboundFrame(rpClient, buf);
            } finally {
                buf.release();
            }
            assertFalse(ch.isOpen(), "session should be closed on invalid tokenLen");
        } finally {
            ch.finishAndReleaseAll();
            server.close();
        }
    }

    @Test
    void serverRejectsOversizedRegisterPayloadLen() {
        RrpConfig conf = new RrpConfig();
        conf.setToken("t");
        conf.setBindPort(0);
        RrpServer server = new RrpServer(conf);
        EmbeddedChannel ch = new EmbeddedChannel();
        try {
            EmbeddedTransportSession session = new EmbeddedTransportSession(1L, ch);
            RrpServer.RpClient rpClient = new RrpServer.RpClient(server, session);
            server.clientsBySessionId.put(1L, rpClient);

            byte[] token = "t".getBytes(StandardCharsets.US_ASCII);
            ByteBuf buf = Unpooled.buffer();
            buf.writeByte(RrpConfig.ACTION_REGISTER);
            buf.writeInt(token.length);
            buf.writeBytes(token);
            buf.writeInt(RrpServer.MAX_REGISTER_BYTES + 1);
            try {
                server.handleInboundFrame(rpClient, buf);
            } finally {
                buf.release();
            }
            assertFalse(ch.isOpen(), "session should be closed on invalid register len");
        } finally {
            ch.finishAndReleaseAll();
            server.close();
        }
    }

    @Test
    void serverRejectsLegacyJdkRegisterPayload() {
        RrpConfig conf = new RrpConfig();
        conf.setToken("t");
        conf.setBindPort(0);
        RrpServer server = new RrpServer(conf);
        EmbeddedChannel ch = new EmbeddedChannel();
        try {
            EmbeddedTransportSession session = new EmbeddedTransportSession(1L, ch);
            RrpServer.RpClient rpClient = new RrpServer.RpClient(server, session);
            server.clientsBySessionId.put(1L, rpClient);

            byte[] tokenBytes = "t".getBytes(StandardCharsets.US_ASCII);
            byte[] data = JdkAndJsonSerializer.DEFAULT.serializeToBytes(
                    Collections.singletonList(newProxy("jdk", 19009, "u1:p1")));
            ByteBuf buf = Unpooled.buffer();
            buf.writeByte(RrpConfig.ACTION_REGISTER);
            buf.writeInt(tokenBytes.length);
            buf.writeBytes(tokenBytes);
            buf.writeInt(data.length);
            buf.writeBytes(data);
            try {
                server.handleInboundFrame(rpClient, buf);
            } finally {
                buf.release();
            }
            assertFalse(ch.isOpen(), "legacy JDK register payload should close session");
            assertTrue(server.proxiesByPort.isEmpty(), "legacy JDK register payload must not register proxy");
        } finally {
            ch.finishAndReleaseAll();
            server.close();
        }
    }

    @Test
    void clientRejectsInvalidIdLen() {
        RrpConfig conf = new RrpConfig();
        conf.setEnableReconnect(false);
        conf.setProxies(Collections.emptyList());
        RrpClient client = new RrpClient(conf);
        EmbeddedChannel ch = new EmbeddedChannel();
        try {
            EmbeddedTransportSession session = new EmbeddedTransportSession(2L, ch);
            ByteBuf buf = Unpooled.buffer();
            buf.writeByte(RrpConfig.ACTION_FORWARD);
            buf.writeInt(2090);
            buf.writeInt(RrpServer.MAX_CHANNEL_ID_LEN + 1);
            try {
                client.handleServerFrame(session, buf);
            } finally {
                buf.release();
            }
            assertFalse(ch.isOpen(), "session should be closed on invalid idLen");
        } finally {
            ch.finishAndReleaseAll();
            client.close();
        }
    }

    @Test
    void clientIgnoresUnknownRemotePort() {
        RrpConfig conf = new RrpConfig();
        conf.setEnableReconnect(false);
        conf.setProxies(Collections.emptyList());
        RrpClient client = new RrpClient(conf);
        EmbeddedChannel ch = new EmbeddedChannel();
        try {
            EmbeddedTransportSession session = new EmbeddedTransportSession(3L, ch);
            ByteBuf buf = Unpooled.buffer();
            buf.writeByte(RrpConfig.ACTION_FORWARD);
            buf.writeInt(2090);
            byte[] id = "abc".getBytes(StandardCharsets.US_ASCII);
            buf.writeInt(id.length);
            buf.writeBytes(id);
            buf.writeBytes(new byte[]{1, 2, 3});
            try {
                client.handleServerFrame(session, buf);
            } finally {
                buf.release();
            }
            assertTrue(ch.isOpen(), "session should remain open for unknown remotePort");
        } finally {
            ch.finishAndReleaseAll();
            client.close();
        }
    }

    @Test
    void clientIgnoresHeartbeat() {
        RrpConfig conf = new RrpConfig();
        conf.setEnableReconnect(false);
        conf.setProxies(Collections.emptyList());
        RrpClient client = new RrpClient(conf);
        EmbeddedChannel ch = new EmbeddedChannel();
        try {
            EmbeddedTransportSession session = new EmbeddedTransportSession(4L, ch);
            ByteBuf buf = Unpooled.buffer(1);
            buf.writeByte(RrpConfig.ACTION_HEARTBEAT);
            try {
                client.handleServerFrame(session, buf);
            } finally {
                buf.release();
            }
            assertTrue(ch.isOpen());
            Object out = ch.readOutbound();
            if (out != null) {
                ReferenceCountUtil.release(out);
            }
            assertNull(out);
        } finally {
            ch.finishAndReleaseAll();
            client.close();
        }
    }

    @Test
    void serverRepliesHeartbeat() {
        RrpConfig conf = new RrpConfig();
        conf.setToken("tok-hb");
        conf.setBindPort(0);
        RrpServer server = new RrpServer(conf);
        EmbeddedChannel ch = new EmbeddedChannel();
        try {
            EmbeddedTransportSession session = new EmbeddedTransportSession(5L, ch);
            RrpServer.RpClient rpClient = new RrpServer.RpClient(server, session);
            server.clientsBySessionId.put(5L, rpClient);

            ByteBuf buf = Unpooled.buffer(1);
            buf.writeByte(RrpConfig.ACTION_HEARTBEAT);
            try {
                server.handleInboundFrame(rpClient, buf);
            } finally {
                buf.release();
            }

            ByteBuf out = ch.readOutbound();
            assertNotNull(out, "server should reply heartbeat");
            try {
                assertEquals(RrpConfig.ACTION_HEARTBEAT, out.readByte());
            } finally {
                out.release();
            }
        } finally {
            ch.finishAndReleaseAll();
            server.close();
        }
    }

    @Test
    void clientLocalAddressIsUniquePerProxy() {
        EmbeddedChannel serverChannel = new EmbeddedChannel();
        RrpClient.RpClientProxy p1 = null;
        RrpClient.RpClientProxy p2 = null;
        try {
            p1 = new RrpClient.RpClientProxy(newProxy("p1", 19111, "u1:p1"), serverChannel);
            p2 = new RrpClient.RpClientProxy(newProxy("p2", 19112, "u1:p1"), serverChannel);
            assertNotEquals(p1.localEndpoint, p2.localEndpoint, "memory LocalAddress must be unique per proxy");
        } finally {
            if (p1 != null) p1.close();
            if (p2 != null) p2.close();
            serverChannel.close();
        }
    }

    @Test
    void localRelayBufferQueuesUntilLocalChannelWritable() {
        RrpConfig conf = new RrpConfig();
        conf.setEnableReconnect(false);
        RrpClient client = new RrpClient(conf);
        EmbeddedChannel serverChannel = new EmbeddedChannel();
        EmbeddedChannel localChannel = new EmbeddedChannel();
        RrpClient.LocalRelayBuffer relayBuffer = new RrpClient.LocalRelayBuffer(client, serverChannel);
        localChannel.attr(RrpClient.ATTR_LOCAL_RELAY_BUF).set(relayBuffer);
        localChannel.unsafe().outboundBuffer().setUserDefinedWritability(1, false);

        ByteBuf payload = Unpooled.wrappedBuffer("local-relay".getBytes(StandardCharsets.US_ASCII));
        assertTrue(relayBuffer.offer(localChannel, payload));
        localChannel.runPendingTasks();

        assertNull(localChannel.readOutbound());
        assertEquals("local-relay".getBytes(StandardCharsets.US_ASCII).length, relayBuffer.pendingBytes.get());

        localChannel.unsafe().outboundBuffer().setUserDefinedWritability(1, true);
        relayBuffer.scheduleDrain(localChannel);
        localChannel.runPendingTasks();

        ByteBuf flushed = localChannel.readOutbound();
        assertNotNull(flushed);
        try {
            assertEquals("local-relay", flushed.toString(StandardCharsets.US_ASCII));
        } finally {
            flushed.release();
            client.close();
            serverChannel.close();
            localChannel.close();
        }
        assertEquals(0, relayBuffer.pendingBytes.get());
    }

    @Test
    void localRelayBufferClosesSlowLocalChannelWhenQueuedBytesOverflow() {
        RrpConfig conf = new RrpConfig();
        conf.setEnableReconnect(false);
        RrpClient client = new RrpClient(conf);
        EmbeddedChannel serverChannel = new EmbeddedChannel();
        EmbeddedChannel localChannel = new EmbeddedChannel();
        RrpClient.LocalRelayBuffer relayBuffer = new RrpClient.LocalRelayBuffer(client, serverChannel);
        localChannel.unsafe().outboundBuffer().setUserDefinedWritability(1, false);

        ByteBuf payload = Unpooled.buffer(RrpClient.MAX_PENDING_FORWARD_BYTES + 1);
        payload.writeZero(RrpClient.MAX_PENDING_FORWARD_BYTES + 1);

        assertFalse(relayBuffer.offer(localChannel, payload));
        localChannel.runPendingTasks();

        assertFalse(localChannel.isOpen());
        assertEquals(0, relayBuffer.pendingBytes.get());
        assertNull(localChannel.readOutbound());
        client.close();
        serverChannel.close();
    }

    @Test
    void serverRelayBufferQueuesUntilServerChannelWritableAndTogglesLocalRead() {
        EmbeddedChannel serverChannel = new EmbeddedChannel();
        EmbeddedChannel localChannel = new EmbeddedChannel();
        RrpClient.ServerRelayBuffer relayBuffer = new RrpClient.ServerRelayBuffer(localChannel);
        serverChannel.unsafe().outboundBuffer().setUserDefinedWritability(1, false);

        ByteBuf payload = Unpooled.wrappedBuffer("server-relay".getBytes(StandardCharsets.US_ASCII));
        assertTrue(relayBuffer.offer(serverChannel, payload));
        serverChannel.runPendingTasks();

        assertFalse(localChannel.config().isAutoRead());
        assertNull(serverChannel.readOutbound());

        serverChannel.unsafe().outboundBuffer().setUserDefinedWritability(1, true);
        relayBuffer.scheduleDrain(serverChannel);
        serverChannel.runPendingTasks();

        ByteBuf flushed = serverChannel.readOutbound();
        assertNotNull(flushed);
        try {
            assertEquals("server-relay", flushed.toString(StandardCharsets.US_ASCII));
        } finally {
            flushed.release();
            serverChannel.close();
            localChannel.close();
        }
        assertTrue(localChannel.config().isAutoRead());
        assertEquals(0, relayBuffer.pendingBytes.get());
    }

    @Test
    void serverRelayBufferClosesLocalChannelWhenQueuedBytesOverflow() {
        EmbeddedChannel serverChannel = new EmbeddedChannel();
        EmbeddedChannel localChannel = new EmbeddedChannel();
        RrpClient.ServerRelayBuffer relayBuffer = new RrpClient.ServerRelayBuffer(localChannel);
        serverChannel.unsafe().outboundBuffer().setUserDefinedWritability(1, false);

        ByteBuf payload = Unpooled.buffer(RrpClient.MAX_PENDING_FORWARD_BYTES + 1);
        payload.writeZero(RrpClient.MAX_PENDING_FORWARD_BYTES + 1);

        assertFalse(relayBuffer.offer(serverChannel, payload));
        localChannel.runPendingTasks();

        assertFalse(localChannel.isOpen());
        assertEquals(0, relayBuffer.pendingBytes.get());
        assertNull(serverChannel.readOutbound());
        serverChannel.close();
    }

    @Test
    void remoteChannelIdRemoveDoesNotDropReplacementChannel() {
        EmbeddedChannel clientChannel = new EmbeddedChannel();
        EmbeddedChannel remoteChannel = new EmbeddedChannel();
        EmbeddedChannel replacement = new EmbeddedChannel();
        EmbeddedTransportSession session = new EmbeddedTransportSession(6L, clientChannel);
        RrpServer.RpClient rpClient = new RrpServer.RpClient(null, session);
        RrpServer.RpClientProxy proxy = new RrpServer.RpClientProxy(null, rpClient,
                newProxy("generated-id", 19121, "u1:p1"), new ServerBootstrap());
        try {
            remoteChannel.attr(RrpConfig.ATTR_SVR_CLI).set(rpClient);
            remoteChannel.attr(RrpConfig.ATTR_SVR_PROXY).set(proxy);
            remoteChannel.pipeline().addLast(RrpServer.RemoteServerHandler.DEFAULT);

            String channelId = remoteChannel.attr(RrpServer.ATTR_REMOTE_CHANNEL_ID).get();
            if (channelId == null) {
                remoteChannel.pipeline().fireChannelActive();
                channelId = remoteChannel.attr(RrpServer.ATTR_REMOTE_CHANNEL_ID).get();
            }

            assertNotNull(channelId);
            assertEquals(remoteChannel, proxy.remoteClients.get(channelId));

            proxy.remoteClients.put(channelId, replacement);
            remoteChannel.pipeline().fireChannelInactive();

            assertEquals(replacement, proxy.remoteClients.get(channelId));
        } finally {
            releaseOutbound(clientChannel);
            clientChannel.close();
            remoteChannel.close();
            replacement.close();
        }
    }

    @Test
    void serverRetryRegisterAfterBindFailureShouldSucceed() throws Exception {
        RrpConfig conf = new RrpConfig();
        conf.setToken("t");
        conf.setBindPort(0);
        RrpServer server = new RrpServer(conf);
        EmbeddedChannel ch = new EmbeddedChannel();
        try (ServerSocket blocker = new ServerSocket(0)) {
            int remotePort = blocker.getLocalPort();
            EmbeddedTransportSession session = new EmbeddedTransportSession(7L, ch);
            RrpServer.RpClient rpClient = new RrpServer.RpClient(server, session);
            server.clientsBySessionId.put(7L, rpClient);

            writeRegister(server, rpClient, "t", newProxy("retry", remotePort, "u1:p1"));
            waitFor(() -> !rpClient.proxyMap.containsKey(remotePort), 5000, "failed bind should be cleaned up");

            blocker.close();

            writeRegister(server, rpClient, "t", newProxy("retry", remotePort, "u1:p1"));
            waitFor(() -> {
                RrpServer.RpClientProxy proxy = rpClient.proxyMap.get(remotePort);
                return proxy != null && proxy.remoteServerChannel != null && proxy.remoteServerChannel.isActive();
            }, 5000, "retry register should bind after cleanup");
        } finally {
            ch.finishAndReleaseAll();
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

        assertNull(remoteChannel.readOutbound());
        assertEquals("relay-data".getBytes(StandardCharsets.US_ASCII).length, relayBuffer.pendingBytes.get());

        remoteChannel.unsafe().outboundBuffer().setUserDefinedWritability(1, true);
        relayBuffer.scheduleDrain(remoteChannel);
        remoteChannel.runPendingTasks();

        ByteBuf flushed = remoteChannel.readOutbound();
        assertNotNull(flushed);
        assertEquals("relay-data", flushed.toString(StandardCharsets.US_ASCII));
        flushed.release();
        assertEquals(0, relayBuffer.pendingBytes.get());
        remoteChannel.close();
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

        assertFalse(remoteChannel.isOpen());
        assertEquals(0, relayBuffer.pendingBytes.get());
        assertNull(remoteChannel.readOutbound());
    }

    @Test
    void emptyTokenRejectedByDefaultAndAllowedOnlyForLocalLoopbackWithoutRawRelay() throws Exception {
        // 1. Default RrpServer without token fails closed
        RrpConfig serverNoToken = new RrpConfig();
        serverNoToken.setBindPort(0);
        assertThrows(IllegalArgumentException.class, () -> new RrpServer(serverNoToken));

        // 2. Default RrpClient without token fails closed on connect
        RrpConfig clientNoToken = new RrpConfig();
        clientNoToken.setServerEndpoint("127.0.0.1:19999");
        clientNoToken.setEnableReconnect(false);
        RrpClient c1 = new RrpClient(clientNoToken);
        try {
            assertThrows(IllegalArgumentException.class, c1::connectAsync);
        } finally {
            c1.close();
        }

        // 3. Unauthenticated client with nodeSecret fails closed even with allowUnauthenticatedLocal=true
        RrpConfig clientRawNoToken = new RrpConfig();
        clientRawNoToken.setServerEndpoint("127.0.0.1:19999");
        clientRawNoToken.setAllowUnauthenticatedLocal(true);
        clientRawNoToken.setNodeId("node-x");
        clientRawNoToken.setNodeSecret("secret-x");
        RrpClient c2 = new RrpClient(clientRawNoToken);
        try {
            assertThrows(IllegalArgumentException.class, c2::connectAsync);
        } finally {
            c2.close();
        }

        // 4. allowUnauthenticatedLocal=true forbids non-loopback client endpoint
        RrpConfig clientRemoteNoToken = new RrpConfig();
        clientRemoteNoToken.setServerEndpoint("198.51.100.10:19999");
        clientRemoteNoToken.setAllowUnauthenticatedLocal(true);
        RrpClient c3 = new RrpClient(clientRemoteNoToken);
        try {
            assertThrows(SecurityException.class, c3::connectAsync);
        } finally {
            c3.close();
        }

        // 5. allowUnauthenticatedLocal=true works for local loopback non-RAW server
        RrpConfig localServer = new RrpConfig();
        localServer.setBindPort(0);
        localServer.setEnableRawRelay(false);
        localServer.setAllowUnauthenticatedLocal(true);
        RrpServer svr = new RrpServer(localServer);
        try {
            assertTrue(svr.listenPort() > 0);
        } finally {
            svr.close();
        }
    }

    @Test
    @Timeout(15)
    void clientInactiveClearsStaleProxyContextsAndDisconnectClosesServerChannel() throws Exception {
        int bindPort, remotePort;
        try (ServerSocket c = new ServerSocket(0); ServerSocket r = new ServerSocket(0)) {
            bindPort = c.getLocalPort();
            remotePort = r.getLocalPort();
        }
        RrpConfig sConf = new RrpConfig();
        sConf.setToken("tok-disc");
        sConf.setBindPort(bindPort);
        RrpServer server = new RrpServer(sConf);

        RrpConfig cConf = new RrpConfig();
        cConf.setToken("tok-disc");
        cConf.setServerEndpoint("127.0.0.1:" + bindPort);
        cConf.setEnableReconnect(false);
        cConf.setHeartbeatSeconds(0);
        RrpConfig.Proxy p = new RrpConfig.Proxy();
        p.setName("disc-proxy");
        p.setRemotePort(remotePort);
        cConf.setProxies(Collections.singletonList(p));
        RrpClient client = new RrpClient(cConf);
        try {
            client.connectAsync().get(5, TimeUnit.SECONDS);
            waitFor(() -> server.proxiesByPort.containsKey(remotePort) && client.proxyMap.containsKey(remotePort),
                    5000, "proxy must register on both client and server");

            // Closing server drops control channel -> client onSessionDisconnected clears stale proxyMap
            server.close();
            waitFor(() -> client.proxyMap.isEmpty(), 5000, "client must clear stale proxy contexts on disconnect");
        } finally {
            client.close();
            server.close();
        }
    }

    @Test
    void endpointTracerIgnoresLocalAddressChannels() {
        EmbeddedChannel localChannel = new EmbeddedChannel();
        localChannel.connect(new LocalAddress("rrp-local"));
        assertEquals(Sockets.newAnyEndpoint(0), EndpointTracer.TCP.head(localChannel));
        localChannel.close();
    }

    static void writeRegister(RrpServer server, RrpServer.RpClient rpClient, String token, RrpConfig.Proxy proxy) {
        ByteBuf buf = Unpooled.buffer();
        buf.writeByte(RrpConfig.ACTION_REGISTER);
        byte[] tokenBytes = token.getBytes(StandardCharsets.US_ASCII);
        buf.writeInt(tokenBytes.length);
        buf.writeBytes(tokenBytes);
        byte[] data = FurySerializer.DEFAULT.serializeToBytes(Collections.singletonList(proxy));
        buf.writeInt(data.length);
        buf.writeBytes(data);
        try {
            server.handleInboundFrame(rpClient, buf);
        } finally {
            buf.release();
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
        req[1] = 0x01;
        req[2] = 0x00;
        req[3] = 0x01;
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
            ReferenceCountUtil.release(msg);
        }
    }
}
