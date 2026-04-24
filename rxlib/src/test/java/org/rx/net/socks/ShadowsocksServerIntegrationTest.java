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
import org.rx.net.socks.upstream.Upstream;
import org.rx.net.socks.encryption.CipherKind;
import org.rx.net.socks.encryption.ICrypto;
import org.rx.net.support.UnresolvedEndpoint;
import org.rx.net.support.UpstreamSupport;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Slf4j
class ShadowsocksServerIntegrationTest {
    static final int TCP_ECHO_PORT = 16299;
    static final int UDP_ECHO_PORT = 16300;
    static final int UDP_ALT_ECHO_PORT = 16301;
    static ServerBootstrap tcpEchoBootstrap;
    static Channel tcpEchoChannel;
    static Channel udpEchoChannel;
    static Channel udpAltEchoChannel;
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
        udpAltEchoChannel = udpEchoBootstrap.bind(Sockets.newAnyEndpoint(UDP_ALT_ECHO_PORT)).sync().channel();

        Thread.sleep(300);
    }

    @AfterAll
    static void teardown() {
        if (tcpEchoChannel != null) tcpEchoChannel.close();
        if (tcpEchoBootstrap != null) Sockets.closeBootstrap(tcpEchoBootstrap);
        if (udpEchoChannel != null) udpEchoChannel.close();
        if (udpAltEchoChannel != null) udpAltEchoChannel.close();
    }

    @Test
    @SneakyThrows
    @Timeout(value = 15)
    void shadowsocksTcpConnect_e2e() {
        int proxyPort = 16280;
        String method = CipherKind.AES_256_GCM.getCipherName();
        String password = "test-password";
        ShadowsocksConfig config = new ShadowsocksConfig(Sockets.newAnyEndpoint(proxyPort), method, password);
        config.setDebug(true); // 开启 debug
        ShadowsocksServer server = new ShadowsocksServer(config);

        try {
            Thread.sleep(300);
            try (Socket s = new Socket("127.0.0.1", proxyPort)) {
                s.setSoTimeout(4000);
                OutputStream out = s.getOutputStream();
                InputStream in = s.getInputStream();

                ICrypto crypto = ICrypto.get(method, password);
                crypto.setForUdp(false);

                // 1) Send encrypted address + payload
                ByteBuf buf = Unpooled.buffer();
                UdpManager.encode(buf, "127.0.0.1", TCP_ECHO_PORT);
                String message = "ss-tcp-e2e";
                buf.writeBytes(message.getBytes(StandardCharsets.UTF_8));
                
                ByteBuf encrypted = crypto.encrypt(buf);
                out.write(toBytes(encrypted));
                out.flush();

                // 2) Read response (encrypted payload)
                // SS TCP response includes initial salt, then chunks.
                byte[] backEncrypted = readAtLeast(in, 1, 1024, 3000);
                ByteBuf decrypted = crypto.decrypt(Unpooled.wrappedBuffer(backEncrypted));
                assertEquals(message, decrypted.toString(StandardCharsets.UTF_8));
                decrypted.release();

                // 3) Additional rounds
                for (int i = 0; i < 10; i++) {
                    String loopMsg = "loop-msg-" + i;
                    ByteBuf loopBuf = Unpooled.buffer();
                    loopBuf.writeBytes(loopMsg.getBytes(StandardCharsets.UTF_8));
                    ByteBuf loopEnc = crypto.encrypt(loopBuf);
                    out.write(toBytes(loopEnc));
                    out.flush();

                    byte[] loopBack = readAtLeast(in, 1, 1024, 3000);
                    ByteBuf loopDec = crypto.decrypt(Unpooled.wrappedBuffer(loopBack));
                    assertEquals(loopMsg, loopDec.toString(StandardCharsets.UTF_8));
                    loopDec.release();
                }
            }
        } finally {
            server.close();
        }
    }

    @Test
    @SneakyThrows
    @Timeout(value = 15)
    void shadowsocksUdpRelay_e2e() {
        int proxyPort = 16281;
        String method = CipherKind.AES_256_GCM.getCipherName();
        String password = "test-password";
        ShadowsocksConfig config = new ShadowsocksConfig(Sockets.newAnyEndpoint(proxyPort), method, password);
        config.setDebug(true);
        ShadowsocksServer server = new ShadowsocksServer(config);

        try {
            Thread.sleep(300);
            DatagramSocket sock = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
            sock.setSoTimeout(4000);
            try {
                ICrypto crypto = ICrypto.get(method, password);
                crypto.setForUdp(true);

                // 1) Send encrypted address + payload
                ByteBuf buf = Unpooled.buffer();
                UdpManager.encode(buf, "127.0.0.1", UDP_ECHO_PORT);
                byte[] payload = "ss-udp-e2e".getBytes(StandardCharsets.UTF_8);
                buf.writeBytes(payload);
                
                ByteBuf encrypted = crypto.encrypt(buf);
                byte[] req = toBytes(encrypted);
                sock.send(new java.net.DatagramPacket(req, req.length, InetAddress.getByName("127.0.0.1"), proxyPort));

                // 2) Receive response (encrypted address + payload)
                byte[] resp = new byte[1024];
                java.net.DatagramPacket p = new java.net.DatagramPacket(resp, resp.length);
                sock.receive(p);

                ByteBuf backEncrypted = Unpooled.wrappedBuffer(resp, 0, p.getLength());
                ByteBuf decrypted = crypto.decrypt(backEncrypted);
                
                // SSProtocolCodec.encode for UDP adds the address
                UnresolvedEndpoint addr = UdpManager.decode(decrypted);
                assertEquals(UDP_ECHO_PORT, addr.getPort());
                
                byte[] echoed = new byte[decrypted.readableBytes()];
                decrypted.readBytes(echoed);
                assertArrayEquals(payload, echoed);
                decrypted.release();

                // 3) Multiple UDP packets
                for (int i = 0; i < 20; i++) {
                    ByteBuf mBuf = Unpooled.buffer();
                    UdpManager.encode(mBuf, "127.0.0.1", UDP_ECHO_PORT);
                    byte[] mPayload = ("udp-burst-" + i).getBytes(StandardCharsets.UTF_8);
                    mBuf.writeBytes(mPayload);
                    
                    ByteBuf mEnc = crypto.encrypt(mBuf);
                    byte[] mReq = toBytes(mEnc);
                    sock.send(new java.net.DatagramPacket(mReq, mReq.length, InetAddress.getByName("127.0.0.1"), proxyPort));

                    byte[] mResp = new byte[1024];
                    java.net.DatagramPacket mp = new java.net.DatagramPacket(mResp, mResp.length);
                    sock.receive(mp);

                    ByteBuf mBackEnc = Unpooled.wrappedBuffer(mResp, 0, mp.getLength());
                    ByteBuf mDec = crypto.decrypt(mBackEnc);
                    UdpManager.decode(mDec); 
                    byte[] mEchoed = new byte[mDec.readableBytes()];
                    mDec.readBytes(mEchoed);
                    assertArrayEquals(mPayload, mEchoed);
                    mDec.release();
                }
            } finally {
                sock.close();
            }
        } finally {
            server.close();
        }
    }

    @Test
    @SneakyThrows
    @Timeout(value = 15)
    void shadowsocksUdpRoute_slowFirstRouteDoesNotBlockSecondClient() {
        int proxyPort = 16282;
        String method = CipherKind.AES_256_GCM.getCipherName();
        String password = "slow-route-password";
        ShadowsocksConfig config = new ShadowsocksConfig(Sockets.newAnyEndpoint(proxyPort), method, password);
        config.setUseDedicatedCryptoGroup(true);
        ShadowsocksServer server = new ShadowsocksServer(config);

        CountDownLatch firstRouteEntered = new CountDownLatch(1);
        CountDownLatch secondRouteEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstRoute = new CountDownLatch(1);
        AtomicInteger routeCalls = new AtomicInteger();
        server.onUdpRoute.replace((s, e) -> {
            int call = routeCalls.incrementAndGet();
            if (call == 1) {
                firstRouteEntered.countDown();
                assertTrue(secondRouteEntered.await(3, TimeUnit.SECONDS), "second route should start while first route is blocked");
                assertTrue(releaseFirstRoute.await(3, TimeUnit.SECONDS), "first route should be released");
            } else if (call == 2) {
                secondRouteEntered.countDown();
            }
            e.setUpstream(new org.rx.net.socks.upstream.Upstream(new UnresolvedEndpoint("127.0.0.1", UDP_ECHO_PORT)));
        });

        DatagramSocket firstClient = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
        DatagramSocket secondClient = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
        firstClient.setSoTimeout(4000);
        secondClient.setSoTimeout(4000);
        try {
            Thread.sleep(300);
            ICrypto firstCrypto = ICrypto.get(method, password);
            ICrypto secondCrypto = ICrypto.get(method, password);
            firstCrypto.setForUdp(true);
            secondCrypto.setForUdp(true);

            byte[] firstPayload = "ss-first-slow-route".getBytes(StandardCharsets.UTF_8);
            byte[] secondPayload = "ss-second-fast-route".getBytes(StandardCharsets.UTF_8);
            sendUdpRequest(firstClient, proxyPort, firstCrypto, firstPayload);
            assertTrue(firstRouteEntered.await(3, TimeUnit.SECONDS), "first route should start");

            sendUdpRequest(secondClient, proxyPort, secondCrypto, secondPayload);
            assertTrue(secondRouteEntered.await(3, TimeUnit.SECONDS), "second route should not be blocked by first route");

            releaseFirstRoute.countDown();
            assertArrayEquals(secondPayload, receiveUdpEcho(secondClient, secondCrypto));
            assertArrayEquals(firstPayload, receiveUdpEcho(firstClient, firstCrypto));
        } finally {
            releaseFirstRoute.countDown();
            firstClient.close();
            secondClient.close();
            server.close();
        }
    }

    @Test
    @SneakyThrows
    @Timeout(value = 30)
    void shadowsocksUdpRoute_sameClientDifferentSocksUpstreams_useSeparateOutboundPools() {
        int proxyAPort = 16283;
        int proxyBPort = 16284;
        int ssPort = 16285;
        String method = CipherKind.AES_256_GCM.getCipherName();
        String password = "ss-dual-upstream-password";

        SocksConfig proxyAConfig = new SocksConfig(proxyAPort);
        proxyAConfig.getWhiteList();
        SocksProxyServer proxyA = new SocksProxyServer(proxyAConfig);

        SocksConfig proxyBConfig = new SocksConfig(proxyBPort);
        proxyBConfig.getWhiteList();
        SocksProxyServer proxyB = new SocksProxyServer(proxyBConfig);

        proxyA.onUdpRoute.replace((s, e) -> {
            if (e.getFirstDestination().getPort() == UDP_ECHO_PORT) {
                e.setUpstream(new Upstream(new UnresolvedEndpoint("127.0.0.1", UDP_ECHO_PORT)));
            } else {
                e.setUpstream(new Upstream(new UnresolvedEndpoint("127.0.0.1", 9)));
            }
        });
        proxyB.onUdpRoute.replace((s, e) -> {
            if (e.getFirstDestination().getPort() == UDP_ALT_ECHO_PORT) {
                e.setUpstream(new Upstream(new UnresolvedEndpoint("127.0.0.1", UDP_ALT_ECHO_PORT)));
            } else {
                e.setUpstream(new Upstream(new UnresolvedEndpoint("127.0.0.1", 9)));
            }
        });

        ShadowsocksConfig ssConfig = new ShadowsocksConfig(Sockets.newAnyEndpoint(ssPort), method, password);
        ShadowsocksServer ssServer = new ShadowsocksServer(ssConfig);

        UpstreamSupport supportA = new UpstreamSupport(new AuthenticEndpoint(new InetSocketAddress("127.0.0.1", proxyAPort), null, null), null);
        UpstreamSupport supportB = new UpstreamSupport(new AuthenticEndpoint(new InetSocketAddress("127.0.0.1", proxyBPort), null, null), null);
        SocksConfig sharedUpstreamConfig = new SocksConfig(0);
        ssServer.onUdpRoute.replace((s, e) -> {
            int port = e.getFirstDestination().getPort();
            if (port == UDP_ECHO_PORT) {
                e.setUpstream(new SocksUdpUpstream(e.getFirstDestination(), sharedUpstreamConfig, supportA));
                return;
            }
            if (port == UDP_ALT_ECHO_PORT) {
                e.setUpstream(new SocksUdpUpstream(e.getFirstDestination(), sharedUpstreamConfig, supportB));
                return;
            }
            throw new IllegalStateException("unexpected ss udp route " + e.getFirstDestination());
        });

        DatagramSocket client = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
        client.setSoTimeout(4000);
        try {
            Thread.sleep(1000);
            ICrypto crypto = ICrypto.get(method, password, true);

            byte[] firstPayload = "ss-via-proxy-a".getBytes(StandardCharsets.UTF_8);
            sendUdpRequest(client, ssPort, crypto, firstPayload, UDP_ECHO_PORT);
            assertArrayEquals(firstPayload, receiveUdpEcho(client, crypto, UDP_ECHO_PORT));

            byte[] secondPayload = "ss-via-proxy-b".getBytes(StandardCharsets.UTF_8);
            sendUdpRequest(client, ssPort, crypto, secondPayload, UDP_ALT_ECHO_PORT);
            assertArrayEquals(secondPayload, receiveUdpEcho(client, crypto, UDP_ALT_ECHO_PORT));

            waitForCondition(() -> proxyA.udpRelayRegistry.size() == 1, 5000, "proxyA should own one UDP relay");
            waitForCondition(() -> proxyB.udpRelayRegistry.size() == 1, 5000, "proxyB should own one UDP relay");
        } finally {
            client.close();
            ssServer.close();
            proxyA.close();
            proxyB.close();
        }
    }

    @Test
    @SneakyThrows
    @Timeout(value = 40)
    void shadowsocksUdpRoute_rebuildsSocksSessionAfterUpstreamRelayClose() {
        int proxyBPort = 16286;
        int proxyAPort = 16287;
        int ssPort = 16288;
        String method = CipherKind.AES_256_GCM.getCipherName();
        String password = "ss-session-heal-password";

        SocksConfig proxyBConfig = new SocksConfig(proxyBPort);
        proxyBConfig.getWhiteList();
        SocksProxyServer proxyB = new SocksProxyServer(proxyBConfig);

        SocksConfig proxyAConfig = new SocksConfig(proxyAPort);
        proxyAConfig.getWhiteList();
        SocksProxyServer proxyA = new SocksProxyServer(proxyAConfig);

        ShadowsocksConfig ssConfig = new ShadowsocksConfig(Sockets.newAnyEndpoint(ssPort), method, password);
        ShadowsocksServer ssServer = new ShadowsocksServer(ssConfig);

        UpstreamSupport supportA = new UpstreamSupport(new AuthenticEndpoint(new InetSocketAddress("127.0.0.1", proxyAPort), null, null), null);
        UpstreamSupport supportB = new UpstreamSupport(new AuthenticEndpoint(new InetSocketAddress("127.0.0.1", proxyBPort), null, null), null);
        ssServer.onUdpRoute.replace((s, e) -> e.setUpstream(new SocksUdpUpstream(e.getFirstDestination(), new SocksConfig(proxyAPort), supportA)));
        proxyA.onUdpRoute.replace((s, e) -> e.setUpstream(new SocksUdpUpstream(e.getFirstDestination(), new SocksConfig(proxyBPort), supportB)));

        DatagramSocket client = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
        client.setSoTimeout(1500);
        try {
            Thread.sleep(1000);
            ICrypto crypto = ICrypto.get(method, password, true);

            byte[] firstPayload = "ss-session-heal-1".getBytes(StandardCharsets.UTF_8);
            sendUdpRequest(client, ssPort, crypto, firstPayload);
            assertArrayEquals(firstPayload, receiveUdpEcho(client, crypto));

            waitForCondition(() -> proxyB.udpRelayRegistry.size() == 1, 5000, "proxyB should own one UDP relay");
            Channel firstRelay = proxyB.udpRelayRegistry.values().iterator().next();
            firstRelay.close().sync();
            waitForCondition(proxyB.udpRelayRegistry::isEmpty, 5000, "proxyB first relay should close");

            byte[] secondPayload = "ss-session-heal-2".getBytes(StandardCharsets.UTF_8);
            boolean healed = false;
            for (int i = 0; i < 8; i++) {
                sendUdpRequest(client, ssPort, crypto, secondPayload);
                try {
                    assertArrayEquals(secondPayload, receiveUdpEcho(client, crypto));
                    healed = true;
                    break;
                } catch (SocketTimeoutException e) {
                    Thread.sleep(100L);
                }
            }
            assertTrue(healed, "should rebuild SOCKS UDP session after upstream relay close");

            waitForCondition(() -> proxyB.udpRelayRegistry.size() == 1, 5000, "proxyB relay should be recreated");
            Channel secondRelay = proxyB.udpRelayRegistry.values().iterator().next();
            assertNotSame(firstRelay, secondRelay);
        } finally {
            client.close();
            ssServer.close();
            proxyA.close();
            proxyB.close();
        }
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

    static void sendUdpRequest(DatagramSocket socket, int proxyPort, ICrypto crypto, byte[] payload) throws Exception {
        sendUdpRequest(socket, proxyPort, crypto, payload, UDP_ECHO_PORT);
    }

    static void sendUdpRequest(DatagramSocket socket, int proxyPort, ICrypto crypto, byte[] payload, int dstPort) throws Exception {
        ByteBuf buf = Unpooled.buffer();
        UdpManager.encode(buf, "127.0.0.1", dstPort);
        buf.writeBytes(payload);
        byte[] req = toBytes(crypto.encrypt(buf));
        socket.send(new java.net.DatagramPacket(req, req.length, InetAddress.getByName("127.0.0.1"), proxyPort));
    }

    static byte[] receiveUdpEcho(DatagramSocket socket, ICrypto crypto) throws Exception {
        return receiveUdpEcho(socket, crypto, UDP_ECHO_PORT);
    }

    static byte[] receiveUdpEcho(DatagramSocket socket, ICrypto crypto, int expectedPort) throws Exception {
        byte[] resp = new byte[1024];
        java.net.DatagramPacket packet = new java.net.DatagramPacket(resp, resp.length);
        socket.receive(packet);
        ByteBuf decrypted = crypto.decrypt(Unpooled.wrappedBuffer(resp, 0, packet.getLength()));
        try {
            UnresolvedEndpoint addr = UdpManager.decode(decrypted);
            assertEquals(expectedPort, addr.getPort());
            byte[] echoed = new byte[decrypted.readableBytes()];
            decrypted.readBytes(echoed);
            return echoed;
        } finally {
            decrypted.release();
        }
    }

    interface CheckFn {
        boolean eval() throws Exception;
    }

    static void waitForCondition(CheckFn condition, long timeoutMs, String message) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        AssertionError lastError = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (condition.eval()) {
                    return;
                }
            } catch (AssertionError e) {
                lastError = e;
            }
            Thread.sleep(50L);
        }
        if (lastError != null) {
            throw lastError;
        }
        fail(message);
    }

    static byte[] toBytes(ByteBuf buf) {
        byte[] b = new byte[buf.readableBytes()];
        buf.readBytes(b);
        buf.release();
        return b;
    }
}
