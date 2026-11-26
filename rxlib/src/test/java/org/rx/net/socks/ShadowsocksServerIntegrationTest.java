package org.rx.net.socks;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.socket.DatagramPacket;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.*;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.Sockets;
import org.rx.net.socks.upstream.SocksUdpUpstream;
import org.rx.net.socks.upstream.Upstream;
import org.rx.net.socks.encryption.CipherKind;
import org.rx.net.socks.encryption.ICrypto;
import java.net.InetSocketAddress;
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
    static int TCP_ECHO_PORT;
    static int UDP_ECHO_PORT;
    static int UDP_ALT_ECHO_PORT;
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
        tcpEchoChannel = tcpEchoBootstrap.bind(Sockets.newLoopbackEndpoint(0)).sync().channel();
        TCP_ECHO_PORT = ((InetSocketAddress) tcpEchoChannel.localAddress()).getPort();

        udpEchoBootstrap = Sockets.udpBootstrap(null, ob -> ob.pipeline().addLast(new SimpleChannelInboundHandler<DatagramPacket>() {
            @Override
            protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket msg) {
                // Echo back to sender
                ByteBuf content = msg.content().retain();
                ctx.writeAndFlush(new DatagramPacket(content, msg.sender()));
            }
        }));
        udpEchoChannel = udpEchoBootstrap.bind(Sockets.newLoopbackEndpoint(0)).sync().channel();
        UDP_ECHO_PORT = ((InetSocketAddress) udpEchoChannel.localAddress()).getPort();
        udpAltEchoChannel = udpEchoBootstrap.bind(Sockets.newLoopbackEndpoint(0)).sync().channel();
        UDP_ALT_ECHO_PORT = ((InetSocketAddress) udpAltEchoChannel.localAddress()).getPort();

        Thread.sleep(300);
    }

    @AfterAll
    static void teardown() {
        if (tcpEchoChannel != null) tcpEchoChannel.close();
        if (tcpEchoBootstrap != null) Sockets.closeBootstrap(tcpEchoBootstrap);
        if (udpEchoChannel != null) udpEchoChannel.close();
        if (udpAltEchoChannel != null) udpAltEchoChannel.close();
    }

    @ParameterizedTest
    @EnumSource(CipherKind.class)
    @SneakyThrows
    @Timeout(value = 15)
    void shadowsocksTcpConnect_e2e(CipherKind kind) {
        String method = kind.getCipherName();
        String password = "test-password";
        ShadowsocksConfig config = new ShadowsocksConfig(Sockets.newLoopbackEndpoint(0), method, password);
        config.setDebug(true); // 开启 debug
        ShadowsocksServer server = new ShadowsocksServer(config);
        int proxyPort = ((InetSocketAddress) server.tcpChannels.get(0).localAddress()).getPort();

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

    @ParameterizedTest
    @EnumSource(CipherKind.class)
    @SneakyThrows
    @Timeout(value = 15)
    void shadowsocksUdpRelay_e2e(CipherKind kind) {
        String method = kind.getCipherName();
        String password = "test-password";
        ShadowsocksConfig config = new ShadowsocksConfig(Sockets.newLoopbackEndpoint(0), method, password);
        config.setDebug(true);
        ShadowsocksServer server = new ShadowsocksServer(config);
        int proxyPort = ((InetSocketAddress) server.udpChannels.get(0).localAddress()).getPort();

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
                InetSocketAddress addr = UdpManager.decode(decrypted);
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
            e.setUpstream(new org.rx.net.socks.upstream.Upstream(org.rx.net.Sockets.newUnresolvedEndpoint("127.0.0.1", UDP_ECHO_PORT)));
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
                e.setUpstream(new Upstream(org.rx.net.Sockets.newUnresolvedEndpoint("127.0.0.1", UDP_ECHO_PORT)));
            } else {
                e.setUpstream(new Upstream(org.rx.net.Sockets.newUnresolvedEndpoint("127.0.0.1", 9)));
            }
        });
        proxyB.onUdpRoute.replace((s, e) -> {
            if (e.getFirstDestination().getPort() == UDP_ALT_ECHO_PORT) {
                e.setUpstream(new Upstream(org.rx.net.Sockets.newUnresolvedEndpoint("127.0.0.1", UDP_ALT_ECHO_PORT)));
            } else {
                e.setUpstream(new Upstream(org.rx.net.Sockets.newUnresolvedEndpoint("127.0.0.1", 9)));
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

    @Test
    @SneakyThrows
    @Timeout(value = 20)
    void backendClosesFirstWhileClientIdle_flushesTailAndClosesFrontendChannel() {
        String method = CipherKind.CHACHA20_IETF_POLY1305.getCipherName();
        String password = "backend-close-first-password";
        byte[] tailPayload = "tail-bytes-before-upstream-idle-close".getBytes(StandardCharsets.UTF_8);

        int socksPort = 16289;
        SocksConfig proxyConfig = new SocksConfig(socksPort);
        proxyConfig.getWhiteList();
        SocksProxyServer socksUpstream = new SocksProxyServer(proxyConfig);

        ShadowsocksConfig ssConfig = new ShadowsocksConfig(Sockets.newLoopbackEndpoint(0), method, password);
        ssConfig.setReadTimeoutSeconds(0);
        ShadowsocksServer ssServer = new ShadowsocksServer(ssConfig);
        UpstreamSupport support = new UpstreamSupport(new AuthenticEndpoint(new InetSocketAddress("127.0.0.1", socksPort), null, null), null);
        ssServer.onTcpRoute.replace((s, e) -> e.setUpstream(new org.rx.net.socks.upstream.SocksTcpUpstream(e.getFirstDestination(), new SocksConfig(0), support)));

        try (java.net.ServerSocket backend = new java.net.ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
             Socket client = new Socket()) {
            backend.setSoTimeout(5000);
            client.connect(ssServer.tcpChannels.get(0).localAddress(), 5000);
            client.setSoTimeout(5000);

            ICrypto crypto = ICrypto.get(method, password);
            try {
                ByteBuf req = Unpooled.buffer();
                UdpManager.encode(req, "127.0.0.1", backend.getLocalPort());
                req.writeByte('Q');
                client.getOutputStream().write(toBytes(crypto.encrypt(req)));
                client.getOutputStream().flush();

                try (Socket accepted = backend.accept()) {
                    assertEquals('Q', accepted.getInputStream().read());
                    accepted.getOutputStream().write(tailPayload);
                    accepted.getOutputStream().flush();
                }

                // Client intentionally keeps its write side open (simulating idle mobile NAT peer).
                waitForCondition(() -> ssServer.activeChannelCount() == 0, 5000,
                        "SS frontend channel must close when upstream/backend closes first");

                java.io.ByteArrayOutputStream plainOut = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[256];
                int n;
                while ((n = client.getInputStream().read(buf)) != -1) {
                    ByteBuf dec = crypto.decrypt(Unpooled.wrappedBuffer(buf, 0, n));
                    try {
                        byte[] chunk = new byte[dec.readableBytes()];
                        dec.readBytes(chunk);
                        plainOut.write(chunk);
                    } finally {
                        dec.release();
                    }
                }
                assertArrayEquals(tailPayload, plainOut.toByteArray());
            } finally {
                if (crypto instanceof AutoCloseable) {
                    ((AutoCloseable) crypto).close();
                }
            }
        } finally {
            ssServer.close();
            socksUpstream.close();
        }
    }

    @Test
    @SneakyThrows
    @Timeout(value = 30)
    void tcpHalfCloseMatrixAcrossCiphersAndDirectSocksAdaptiveUpstreams() {
        SocksUser adaptiveUser = new SocksUser("adaptive");
        adaptiveUser.setPassword("adaptive-pwd");
        SocksConfig socksCfg = new SocksConfig(0);
        socksCfg.setAdaptiveTcp(true);
        try (SocksProxyServer socksUpstream = new SocksProxyServer(
                socksCfg, new SocksAuthenticator(java.util.Collections.singletonList(adaptiveUser)))) {
            int socksPort = ((InetSocketAddress) socksUpstream.tcpChannels.get(0).localAddress()).getPort();
            AuthenticEndpoint socksAuthEp = new AuthenticEndpoint(
                    new InetSocketAddress("127.0.0.1", socksPort), "adaptive", "adaptive-pwd");
            UpstreamSupport support = new UpstreamSupport(socksAuthEp, null);

            for (CipherKind kind : CipherKind.values()) {
                for (int mode = 0; mode < 3; mode++) {
                    final int upstreamMode = mode; // 0=direct, 1=SOCKS, 2=RSS adaptive TCP
                    String method = kind.getCipherName();
                    String password = "matrix-pwd-" + kind.ordinal() + "-" + mode;
                    ShadowsocksConfig ssConfig = new ShadowsocksConfig(Sockets.newLoopbackEndpoint(0), method, password);
                    ssConfig.setReadTimeoutSeconds(0);
                    try (ShadowsocksServer ssServer = new ShadowsocksServer(ssConfig);
                         java.net.ServerSocket backend = new java.net.ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))) {
                        backend.setSoTimeout(5000);
                        ssServer.onTcpRoute.replace((s, e) -> {
                            if (upstreamMode == 0) {
                                e.setUpstream(new org.rx.net.socks.upstream.Upstream(e.getFirstDestination()));
                            } else {
                                SocksConfig uCfg = new SocksConfig(0);
                                if (upstreamMode == 2) {
                                    uCfg.setAdaptiveTcp(true);
                                }
                                e.setUpstream(new org.rx.net.socks.upstream.SocksTcpUpstream(e.getFirstDestination(), uCfg, support));
                            }
                        });

                        // A. Frontend-first FIN (client shutdownOutput after sending request, including adaptive drain).
                        verifyHalfCloseScenario(ssServer, backend, method, password, "FRONT_FIN");
                        // B. Backend-first EOF while client keeps output open.
                        verifyHalfCloseScenario(ssServer, backend, method, password, "BACK_EOF");
                        // C. Simultaneous FIN from both sides.
                        verifyHalfCloseScenario(ssServer, backend, method, password, "SIMULTANEOUS");
                        // D. Pre-connect FIN (client sends header + payload + FIN in one burst).
                        verifyHalfCloseScenario(ssServer, backend, method, password, "PRE_CONNECT_FIN");
                    }
                }
            }
        }
    }

    private static void verifyHalfCloseScenario(ShadowsocksServer ssServer, java.net.ServerSocket backend,
                                                String method, String password, String scenario) throws Exception {
        byte[] reqPayload = ("req-" + scenario + "-" + method).getBytes(StandardCharsets.UTF_8);
        byte[] respPayload = ("resp-tail-" + scenario + "-" + method + "-0123456789abcdef").getBytes(StandardCharsets.UTF_8);
        try (Socket client = new Socket()) {
            client.connect(ssServer.tcpChannels.get(0).localAddress(), 5000);
            client.setSoTimeout(5000);
            ICrypto crypto = ICrypto.get(method, password);
            try {
                ByteBuf req = Unpooled.buffer();
                UdpManager.encode(req, "127.0.0.1", backend.getLocalPort());
                req.writeBytes(reqPayload);
                client.getOutputStream().write(toBytes(crypto.encrypt(req)));
                client.getOutputStream().flush();
                if ("PRE_CONNECT_FIN".equals(scenario)) {
                    client.shutdownOutput();
                }

                try (Socket accepted = backend.accept()) {
                    accepted.setSoTimeout(5000);
                    byte[] gotReq = readAtLeast(accepted.getInputStream(), reqPayload.length, reqPayload.length + 16, 5000);
                    assertArrayEquals(reqPayload, gotReq);

                    if ("FRONT_FIN".equals(scenario)) {
                        client.shutdownOutput();
                        assertEquals(-1, accepted.getInputStream().read());
                        accepted.getOutputStream().write(respPayload);
                        accepted.getOutputStream().flush();
                    } else if ("BACK_EOF".equals(scenario)) {
                        accepted.getOutputStream().write(respPayload);
                        accepted.getOutputStream().flush();
                    } else if ("SIMULTANEOUS".equals(scenario)) {
                        accepted.getOutputStream().write(respPayload);
                        accepted.getOutputStream().flush();
                        client.shutdownOutput();
                        accepted.shutdownOutput();
                    } else if ("PRE_CONNECT_FIN".equals(scenario)) {
                        assertEquals(-1, accepted.getInputStream().read());
                        accepted.getOutputStream().write(respPayload);
                        accepted.getOutputStream().flush();
                    }
                }

                java.io.ByteArrayOutputStream plainOut = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[256];
                int n;
                while ((n = client.getInputStream().read(buf)) != -1) {
                    ByteBuf dec = crypto.decrypt(Unpooled.wrappedBuffer(buf, 0, n));
                    try {
                        byte[] chunk = new byte[dec.readableBytes()];
                        dec.readBytes(chunk);
                        plainOut.write(chunk);
                    } finally {
                        dec.release();
                    }
                }
                assertArrayEquals(respPayload, plainOut.toByteArray());
                waitForCondition(() -> ssServer.activeChannelCount() == 0, 5000,
                        "SS frontend channels must drain to 0 after " + scenario + " (" + method + ")");
            } finally {
                ((AutoCloseable) crypto).close();
            }
        }
    }

    @Test
    @SneakyThrows
    @Timeout(value = 20)
    void halfCloseWatchCleansStalledBackendButPreservesBackpressuredSlowReader() {
        SocksTcpFrontendRelayHandler.setHalfCloseIdleMillisForTest(250L);
        try {
            String method = CipherKind.CHACHA20_IETF_POLY1305.getCipherName();
            String password = "watch-test-password";
            ShadowsocksConfig ssConfig = new ShadowsocksConfig(Sockets.newLoopbackEndpoint(0), method, password);
            ssConfig.setReadTimeoutSeconds(0);
            try (ShadowsocksServer ssServer = new ShadowsocksServer(ssConfig);
                 java.net.ServerSocket backend = new java.net.ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))) {
                backend.setSoTimeout(5000);
                ssServer.onTcpRoute.replace((s, e) -> e.setUpstream(new org.rx.net.socks.upstream.Upstream(e.getFirstDestination())));

                // 1. Unresponsive backend after client shutdownOutput: HalfCloseWatch cleans up within ~250-600ms.
                try (Socket client = new Socket()) {
                    client.connect(ssServer.tcpChannels.get(0).localAddress(), 5000);
                    client.setSoTimeout(3000);
                    ICrypto crypto = ICrypto.get(method, password);
                    Socket stalledAccepted = null;
                    try {
                        ByteBuf req = Unpooled.buffer();
                        UdpManager.encode(req, "127.0.0.1", backend.getLocalPort());
                        req.writeByte('S');
                        client.getOutputStream().write(toBytes(crypto.encrypt(req)));
                        client.getOutputStream().flush();
                        stalledAccepted = backend.accept();
                        assertEquals('S', stalledAccepted.getInputStream().read());
                        client.shutdownOutput();
                        assertEquals(-1, stalledAccepted.getInputStream().read());
                        // Backend stays silent and open; HalfCloseWatch must close frontend & backend.
                        waitForCondition(() -> ssServer.activeChannelCount() == 0, 3000,
                                "HalfCloseWatch must close stalled half-closed session");
                        assertEquals(-1, client.getInputStream().read());
                    } finally {
                        if (stalledAccepted != null) stalledAccepted.close();
                        ((AutoCloseable) crypto).close();
                    }
                }

                // 2. Backpressured slow reader: backend floods 1.5 MiB after client shutdownOutput while client pauses reading
                // for 600ms (> 2x HalfCloseWatch timeout); connection MUST stay alive and deliver all 1.5 MiB!
                final int totalBytes = 1536 * 1024;
                byte[] bigPayload = new byte[totalBytes];
                new java.util.Random(77).nextBytes(bigPayload);
                try (Socket client = new Socket()) {
                    client.setReceiveBufferSize(16 * 1024);
                    client.connect(ssServer.tcpChannels.get(0).localAddress(), 5000);
                    client.setSoTimeout(10000);
                    ICrypto crypto = ICrypto.get(method, password);
                    Thread writerThread = null;
                    try {
                        ByteBuf req = Unpooled.buffer();
                        UdpManager.encode(req, "127.0.0.1", backend.getLocalPort());
                        req.writeByte('B');
                        client.getOutputStream().write(toBytes(crypto.encrypt(req)));
                        client.getOutputStream().flush();
                        final Socket accepted = backend.accept();
                        assertEquals('B', accepted.getInputStream().read());
                        client.shutdownOutput();
                        assertEquals(-1, accepted.getInputStream().read());

                        writerThread = new Thread(() -> {
                            try (Socket s = accepted) {
                                s.getOutputStream().write(bigPayload);
                                s.getOutputStream().flush();
                            } catch (Exception ignored) {
                            }
                        }, "slow-reader-backend");
                        writerThread.start();

                        // Pause client reading for 650ms (> 2.5x 250ms watchdog) while inbound buffer is backpressured.
                        Thread.sleep(650L);
                        java.io.ByteArrayOutputStream plainOut = new java.io.ByteArrayOutputStream(totalBytes);
                        byte[] buf = new byte[16384];
                        int n;
                        while ((n = client.getInputStream().read(buf)) != -1) {
                            ByteBuf dec = crypto.decrypt(Unpooled.wrappedBuffer(buf, 0, n));
                            try {
                                byte[] chunk = new byte[dec.readableBytes()];
                                dec.readBytes(chunk);
                                plainOut.write(chunk);
                            } finally {
                                dec.release();
                            }
                        }
                        writerThread.join(5000L);
                        assertArrayEquals(bigPayload, plainOut.toByteArray());
                        waitForCondition(() -> ssServer.activeChannelCount() == 0, 5000,
                                "channels must close cleanly after slow reader finishes");
                    } finally {
                        if (writerThread != null) writerThread.join(2000L);
                        ((AutoCloseable) crypto).close();
                    }
                }
            }
        } finally {
            SocksTcpFrontendRelayHandler.setHalfCloseIdleMillisForTest(0L);
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
            InetSocketAddress addr = UdpManager.decode(decrypted);
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
