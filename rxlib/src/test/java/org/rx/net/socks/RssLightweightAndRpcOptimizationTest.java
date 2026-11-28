package org.rx.net.socks;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.socksx.v5.DefaultSocks5InitialRequest;
import io.netty.handler.codec.socksx.v5.DefaultSocks5PasswordAuthRequest;
import io.netty.handler.codec.socksx.v5.Socks5AuthMethod;
import io.netty.handler.codec.socksx.v5.Socks5ClientEncoder;
import io.netty.handler.codec.socksx.v5.Socks5InitialResponse;
import io.netty.handler.codec.socksx.v5.Socks5InitialResponseDecoder;
import io.netty.handler.codec.socksx.v5.Socks5PasswordAuthResponse;
import io.netty.handler.codec.socksx.v5.Socks5PasswordAuthResponseDecoder;
import io.netty.handler.codec.socksx.v5.Socks5PasswordAuthStatus;
import org.junit.jupiter.api.Test;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.EgressCodec;
import org.rx.net.EgressConfig;
import org.rx.net.HandshakeMode;
import org.rx.net.SecurityMode;
import org.rx.net.Sockets;
import org.rx.net.TransportFlags;
import org.rx.net.rpc.Remoting;
import org.rx.net.rpc.RpcClientConfig;
import org.rx.net.rpc.RpcServerConfig;
import org.rx.net.socks.upstream.SocksTcpUpstream;
import org.rx.net.socks.upstream.SocksUdpUpstream;
import org.rx.net.support.UpstreamSupport;
import org.rx.net.transport.TcpServerConfig;
import org.rx.net.transport.core.TransportConfig;
import org.rx.net.transport.core.TransportCoreCarrier;
import org.rx.net.transport.core.TransportServer;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round 3 comprehensive verification covering §8 (auth fail-closed, independent inflate budget, warm UDP health),
 * §9 (RSS zero-added-RTT LIGHTWEIGHT handshake & pure plain SOCKS5 fast path),
 * §10 (RPC capabilities single-flight cache, plain UDP RPC Group with 0 SOCKS5 TCP control sockets, atomic capacity),
 * §11 (UDP cross-destination reuse & policy isolation, demand-driven single-scheduler TCP warm pool, RPC minPoolSize=0),
 * and §12 (unified SecurityMode.FAST AES-128-GCM + HMAC-SHA256).
 */
class RssLightweightAndRpcOptimizationTest {
    private static final String USER = "alice";
    private static final String PASS = "s3cr3t-pwd-123";

    private static Authenticator singleUserAuth() {
        SocksUser alice = new SocksUser(USER);
        alice.setPassword(PASS);
        return new Authenticator() {
            @Override
            public SocksUser login(String username, String password) {
                return USER.equals(username) && PASS.equals(password) ? alice : null;
            }

            @Override
            public String lookupSecret(String username) {
                return USER.equals(username) ? PASS : null;
            }
        };
    }

    private static int freeTcpPort() throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        }
    }

    private static int[] freeTcpPorts(int count) throws Exception {
        int[] ports = new int[count];
        List<ServerSocket> sockets = new ArrayList<>();
        try {
            for (int i = 0; i < count; i++) {
                ServerSocket ss = new ServerSocket(0);
                sockets.add(ss);
                ports[i] = ss.getLocalPort();
            }
        } finally {
            for (ServerSocket ss : sockets) {
                ss.close();
            }
        }
        return ports;
    }

    private static final class TcpEchoServer implements AutoCloseable {
        final ServerSocket server;
        final AtomicBoolean running = new AtomicBoolean(true);
        final Thread acceptThread;

        TcpEchoServer() throws Exception {
            server = new ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"));
            acceptThread = new Thread(() -> {
                while (running.get()) {
                    try {
                        Socket s = server.accept();
                        Thread t = new Thread(() -> {
                            try (Socket sock = s) {
                                byte[] buf = new byte[4096];
                                InputStream in = sock.getInputStream();
                                OutputStream out = sock.getOutputStream();
                                int n;
                                while ((n = in.read(buf)) != -1) {
                                    out.write(buf, 0, n);
                                    out.flush();
                                }
                            } catch (Exception ignored) {
                            }
                        });
                        t.setDaemon(true);
                        t.start();
                    } catch (Exception ignored) {
                        break;
                    }
                }
            });
            acceptThread.setDaemon(true);
            acceptThread.start();
        }

        int port() {
            return server.getLocalPort();
        }

        @Override
        public void close() throws Exception {
            running.set(false);
            server.close();
        }
    }

    /**
     * Transparent TCP byte-turn sniffer that counts server-side client->server and server->client turns
     * and records the first bytes sent by the client to verify zero RXTC bytes and exact RTT savings.
     */
    private static final class WireSnifferProxy implements AutoCloseable {
        final NioEventLoopGroup group = new NioEventLoopGroup(2);
        final Channel serverChannel;
        final int port;
        final AtomicInteger connections = new AtomicInteger();
        final AtomicInteger c2sTurns = new AtomicInteger();
        final AtomicInteger s2cTurns = new AtomicInteger();
        final ByteArrayOutputStream firstConnClientBytes = new ByteArrayOutputStream();
        final AtomicBoolean rxtcSeen = new AtomicBoolean();

        WireSnifferProxy(InetSocketAddress target) throws Exception {
            ServerBootstrap sb = new ServerBootstrap()
                    .group(group)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            int connIdx = connections.incrementAndGet();
                            Bootstrap b = new Bootstrap()
                                    .group(ch.eventLoop())
                                    .channel(NioSocketChannel.class)
                                    .handler(new ChannelInboundHandlerAdapter() {
                                        @Override
                                        public void channelRead(ChannelHandlerContext ctx, Object msg) {
                                            s2cTurns.incrementAndGet();
                                            ch.writeAndFlush(msg);
                                        }

                                        @Override
                                        public void channelInactive(ChannelHandlerContext ctx) {
                                            ch.close();
                                        }
                                    });
                            ChannelFuture backend = b.connect(target);
                            ch.config().setAutoRead(false);
                            backend.addListener((ChannelFutureListener) f -> {
                                if (f.isSuccess()) {
                                    ch.config().setAutoRead(true);
                                } else {
                                    ch.close();
                                }
                            });
                            ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                                @Override
                                public void channelRead(ChannelHandlerContext ctx, Object msg) {
                                    c2sTurns.incrementAndGet();
                                    if (msg instanceof ByteBuf) {
                                        ByteBuf buf = (ByteBuf) msg;
                                        byte[] copy = new byte[Math.min(buf.readableBytes(), 64)];
                                        buf.getBytes(buf.readerIndex(), copy);
                                        if (connIdx == 1) {
                                            synchronized (firstConnClientBytes) {
                                                if (firstConnClientBytes.size() < 256) {
                                                    firstConnClientBytes.write(copy, 0, copy.length);
                                                }
                                            }
                                        }
                                        if (copy.length >= 4 && copy[0] == 'R' && copy[1] == 'X' && copy[2] == 'T' && copy[3] == 'C') {
                                            rxtcSeen.set(true);
                                        }
                                    }
                                    backend.channel().writeAndFlush(msg);
                                }

                                @Override
                                public void channelInactive(ChannelHandlerContext ctx) {
                                    backend.channel().close();
                                }
                            });
                        }
                    });
            serverChannel = sb.bind("127.0.0.1", 0).sync().channel();
            port = ((InetSocketAddress) serverChannel.localAddress()).getPort();
        }

        void resetCounters() {
            c2sTurns.set(0);
            s2cTurns.set(0);
            rxtcSeen.set(false);
        }

        @Override
        public void close() {
            serverChannel.close().syncUninterruptibly();
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }

    private static byte[] socks5TcpEchoViaClient(int localSocksPort, int targetPort, byte[] payload) throws Exception {
        try (Socket s = new Socket("127.0.0.1", localSocksPort)) {
            s.setSoTimeout(8000);
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();
            // NO_AUTH greeting to local unauthenticated front proxy
            out.write(new byte[]{5, 1, 0});
            out.flush();
            byte[] methodResp = readExact(in, 2);
            assertEquals(5, methodResp[0]);
            assertEquals(0, methodResp[1]);
            // CONNECT 127.0.0.1:targetPort
            out.write(new byte[]{5, 1, 0, 1, 127, 0, 0, 1, (byte) (targetPort >>> 8), (byte) targetPort});
            out.flush();
            byte[] connResp = readExact(in, 10);
            assertEquals(5, connResp[0]);
            assertEquals(0, connResp[1], "SOCKS5 CONNECT failed with status " + connResp[1]);

            out.write(payload);
            out.flush();
            return readExact(in, payload.length);
        }
    }

    private static byte[] readExact(InputStream in, int len) throws Exception {
        byte[] out = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(out, off, len - off);
            if (n == -1) {
                throw new java.io.EOFException("Expected " + len + " bytes, got " + off);
            }
            off += n;
        }
        return out;
    }

    @Test
    void plainNonAeadLightweightUsesPureSocks5ZeroCoreState() throws Exception {
        int[] ports = freeTcpPorts(2);
        SocksConfig serverCfg = new SocksConfig(ports[0]);
        serverCfg.setRssPrivateTransport(true);
        serverCfg.setHandshakeMode(HandshakeMode.LIGHTWEIGHT);
        serverCfg.setEgressConfig(EgressConfig.serverAutoAll());

        try (TcpEchoServer echo = new TcpEchoServer();
             SocksProxyServer remote = new SocksProxyServer(serverCfg, singleUserAuth());
             WireSnifferProxy sniffer = new WireSnifferProxy(new InetSocketAddress("127.0.0.1", ports[0]))) {

            assertFalse(remote.hasCoreReplayCache(), "Replay cache must stay unallocated before any RXTC/0x8c traffic");

            for (EgressCodec egress : new EgressCodec[]{EgressCodec.NONE, EgressCodec.ZLIB_STORED, EgressCodec.HTTP}) {
                SocksConfig frontCfg = new SocksConfig(freeTcpPort());
                SocksConfig outCfg = new SocksConfig();
                outCfg.setRssPrivateTransport(true);
                outCfg.setHandshakeMode(HandshakeMode.LIGHTWEIGHT);
                outCfg.setTransportFlags(TransportFlags.NONE.flags());
                outCfg.setEgressConfig(EgressConfig.client(egress));
                UpstreamSupport up = new UpstreamSupport(
                        new AuthenticEndpoint(new InetSocketAddress("127.0.0.1", sniffer.port), USER, PASS), null);

                try (SocksProxyServer front = new SocksProxyServer(frontCfg)) {
                    front.onTcpRoute.replace((s, e) ->
                            e.setUpstream(new SocksTcpUpstream(e.getFirstDestination(), outCfg, up)));

                    sniffer.resetCounters();
                    byte[] msg = ("hello-plain-" + egress).getBytes(StandardCharsets.UTF_8);
                    byte[] echoed = socks5TcpEchoViaClient(frontCfg.getListenPort(), echo.port(), msg);
                    assertArrayEquals(msg, echoed);
                    assertFalse(sniffer.rxtcSeen.get(), "Plain non-AEAD LIGHTWEIGHT must NOT send RXTC bytes");
                    if (egress == EgressCodec.NONE) {
                        byte[] first = sniffer.firstConnClientBytes.toByteArray();
                        assertTrue(first.length >= 3 && first[0] == 0x05,
                                "Plain NONE LIGHTWEIGHT first wire byte must be standard SOCKS5 0x05");
                    }
                }
            }
            assertFalse(remote.hasCoreReplayCache(),
                    "Pure plain non-AEAD LIGHTWEIGHT must never allocate NonceReplayCache");
        }
    }

    @Test
    void lightweight0x8cPiggybackZeroAddedRttForPlainZlibAndEnhancedOverAuto() throws Exception {
        int[] ports = freeTcpPorts(2);
        SocksConfig serverCfg = new SocksConfig(ports[0]);
        serverCfg.setRssPrivateTransport(true);
        serverCfg.setHandshakeMode(HandshakeMode.LIGHTWEIGHT);
        serverCfg.setAdaptiveTcp(true);
        serverCfg.setDailyPortRange("26200-26203");
        serverCfg.setTransportFlags(TransportFlags.GFW.flags(TransportFlags.COMPRESS_BOTH).flags());
        serverCfg.setEgressConfig(EgressConfig.serverAutoAll());

        try (TcpEchoServer echo = new TcpEchoServer();
             SocksProxyServer remote = new SocksProxyServer(serverCfg, singleUserAuth());
             WireSnifferProxy sniffer = new WireSnifferProxy(new InetSocketAddress("127.0.0.1", ports[0]))) {

            // 1. Compare exact client->server handshake turns for plain + zlib: LIGHTWEIGHT (0x8c) vs CORE (RXTC)
            int lightweightTurns;
            int coreTurns;
            for (EgressCodec egress : new EgressCodec[]{EgressCodec.NONE, EgressCodec.ZLIB_STORED, EgressCodec.HTTP}) {
                SocksConfig frontCfg = new SocksConfig(freeTcpPort());
                SocksConfig lwCfg = new SocksConfig();
                lwCfg.setRssPrivateTransport(true);
                lwCfg.setHandshakeMode(HandshakeMode.LIGHTWEIGHT);
                lwCfg.setTransportFlags(TransportFlags.COMPRESS_BOTH.flags());
                lwCfg.setTcpCompressionLevel(3);
                lwCfg.setEgressConfig(EgressConfig.client(egress));
                UpstreamSupport up = new UpstreamSupport(
                        new AuthenticEndpoint(new InetSocketAddress("127.0.0.1", sniffer.port), USER, PASS), null);

                try (SocksProxyServer front = new SocksProxyServer(frontCfg)) {
                    front.onTcpRoute.replace((s, e) ->
                            e.setUpstream(new SocksTcpUpstream(e.getFirstDestination(), lwCfg, up)));
                    sniffer.resetCounters();
                    byte[] payload = new byte[4096];
                    Arrays.fill(payload, (byte) 'A');
                    assertArrayEquals(payload, socks5TcpEchoViaClient(frontCfg.getListenPort(), echo.port(), payload));
                    assertFalse(sniffer.rxtcSeen.get(), "Non-AEAD plain+zlib LIGHTWEIGHT must use 0x8c, not RXTC");
                }
            }

            // Measure LIGHTWEIGHT vs CORE round-trip count on identical plain + zlib + NONE
            SocksConfig lwMeasure = new SocksConfig();
            lwMeasure.setRssPrivateTransport(true);
            lwMeasure.setHandshakeMode(HandshakeMode.LIGHTWEIGHT);
            lwMeasure.setTransportFlags(TransportFlags.COMPRESS_BOTH.flags());
            lwMeasure.setEgressConfig(EgressConfig.none());

            SocksConfig coreMeasure = new SocksConfig();
            coreMeasure.setRssPrivateTransport(true);
            coreMeasure.setHandshakeMode(HandshakeMode.CORE);
            coreMeasure.setTransportFlags(TransportFlags.COMPRESS_BOTH.flags());
            coreMeasure.setEgressConfig(EgressConfig.none());

            UpstreamSupport upSniff = new UpstreamSupport(
                    new AuthenticEndpoint(new InetSocketAddress("127.0.0.1", sniffer.port), USER, PASS), null);

            SocksConfig f1Cfg = new SocksConfig(freeTcpPort());
            try (SocksProxyServer front = new SocksProxyServer(f1Cfg)) {
                front.onTcpRoute.replace((s, e) -> e.setUpstream(new SocksTcpUpstream(e.getFirstDestination(), lwMeasure, upSniff)));
                sniffer.resetCounters();
                byte[] msg = "rtt-check".getBytes(StandardCharsets.UTF_8);
                assertArrayEquals(msg, socks5TcpEchoViaClient(f1Cfg.getListenPort(), echo.port(), msg));
                lightweightTurns = sniffer.s2cTurns.get();
                assertFalse(sniffer.rxtcSeen.get());
            }

            SocksConfig f2Cfg = new SocksConfig(freeTcpPort());
            try (SocksProxyServer front = new SocksProxyServer(f2Cfg)) {
                front.onTcpRoute.replace((s, e) -> e.setUpstream(new SocksTcpUpstream(e.getFirstDestination(), coreMeasure, upSniff)));
                sniffer.resetCounters();
                byte[] msg = "rtt-check".getBytes(StandardCharsets.UTF_8);
                assertArrayEquals(msg, socks5TcpEchoViaClient(f2Cfg.getListenPort(), echo.port(), msg));
                coreTurns = sniffer.s2cTurns.get();
                assertTrue(sniffer.rxtcSeen.get(), "Explicit CORE mode must send RXTC");
            }
            assertTrue(lightweightTurns < coreTurns,
                    "LIGHTWEIGHT 0x8c must save at least 1 round-trip vs CORE (lw=" + lightweightTurns + ", core=" + coreTurns + ")");

            // 2. Verify enhanced daily profile (adaptiveTcp=true, non-AEAD) over LIGHTWEIGHT 0x8c + AUTO
            SocksConfig dailyCfg = new SocksConfig();
            dailyCfg.setRssPrivateTransport(true);
            dailyCfg.setHandshakeMode(HandshakeMode.LIGHTWEIGHT);
            dailyCfg.setAdaptiveTcp(true);
            dailyCfg.setAdaptiveProfile(1);
            dailyCfg.setUdpHop(true);
            dailyCfg.setDailyUdpMtu(1200);
            dailyCfg.setEgressConfig(EgressConfig.client(EgressCodec.HTTP));
            SocksConfig f3Cfg = new SocksConfig(freeTcpPort());
            try (SocksProxyServer front = new SocksProxyServer(f3Cfg)) {
                front.onTcpRoute.replace((s, e) -> e.setUpstream(new SocksTcpUpstream(e.getFirstDestination(), dailyCfg, upSniff)));
                sniffer.resetCounters();
                byte[] msg = "daily-lightweight-http".getBytes(StandardCharsets.UTF_8);
                assertArrayEquals(msg, socks5TcpEchoViaClient(f3Cfg.getListenPort(), echo.port(), msg));
                assertFalse(sniffer.rxtcSeen.get(), "Non-AEAD daily LIGHTWEIGHT must piggyback on 0x8c");
            }

            // 3. Verify §8.2.5: UpstreamSupport UDP health feedback on Socks5WarmupHandler & closed-generation guard
            UpstreamSupport closedGen = new UpstreamSupport(
                    new AuthenticEndpoint(new InetSocketAddress("127.0.0.1", ports[0]), USER, PASS), null);
            closedGen.markClosed();
            assertTrue(closedGen.isClosed());
            Socks5WarmupHandler warmup = new Socks5WarmupHandler(
                    new InetSocketAddress("127.0.0.1", ports[0]), USER, PASS, 5000);
            warmup.setUpstreamSupport(closedGen);
            assertSame(closedGen, warmup.getUpstreamSupport());
        }
    }

    @Test
    void authFailClosedWrongPasswordNoAuthUnacceptedAndReplayRejected() throws Exception {
        int port = freeTcpPort();
        SocksConfig serverCfg = new SocksConfig(port);
        serverCfg.setRssPrivateTransport(true);
        serverCfg.setHandshakeMode(HandshakeMode.LIGHTWEIGHT);
        serverCfg.setEgressConfig(EgressConfig.serverAutoAll());

        try (SocksProxyServer remote = new SocksProxyServer(serverCfg, singleUserAuth())) {
            // 1. Wrong password on standard 0x02 MUST return FAILURE (never ANONYMOUS)
            try (Socket s = new Socket("127.0.0.1", port)) {
                s.setSoTimeout(3000);
                OutputStream out = s.getOutputStream();
                InputStream in = s.getInputStream();
                out.write(new byte[]{5, 1, 2});
                out.flush();
                byte[] m = readExact(in, 2);
                assertEquals(2, m[1]);
                // RFC 1929 auth with wrong password
                out.write(new byte[]{1, 5, 'a', 'l', 'i', 'c', 'e', 5, 'w', 'r', 'o', 'n', 'g'});
                out.flush();
                byte[] authResp = readExact(in, 2);
                assertEquals(1, authResp[0]);
                assertNotEquals(0, authResp[1], "Wrong password must return non-zero FAILURE status");
            }

            // 2. Offering ONLY NO_AUTH (0x00) to an auth-enabled server MUST return UNACCEPTED (0xFF) per RFC 1928
            try (Socket s = new Socket("127.0.0.1", port)) {
                s.setSoTimeout(3000);
                OutputStream out = s.getOutputStream();
                InputStream in = s.getInputStream();
                out.write(new byte[]{5, 1, 0});
                out.flush();
                byte[] m = readExact(in, 2);
                assertEquals(5, m[0]);
                assertEquals((byte) 0xFF, m[1], "Auth-enabled server must reject NO_AUTH with 0xFF UNACCEPTED");
            }

            // 3. Replay of identical 0x8c client nonce MUST be rejected by NonceReplayCache
            SocksConfig lwCfg = new SocksConfig();
            lwCfg.setRssPrivateTransport(true);
            lwCfg.setHandshakeMode(HandshakeMode.LIGHTWEIGHT);
            lwCfg.setTransportFlags(TransportFlags.COMPRESS_BOTH.flags());
            lwCfg.setEgressConfig(EgressConfig.none());
            EmbeddedChannel helperCh = new EmbeddedChannel();
            byte[] extBytes;
            try {
                helperCh.attr(TransportCoreCarrier.ATTR_CLIENT_SOCKS_CONFIG).set(lwCfg);
                TransportCoreCarrier.LightweightClientState state = TransportCoreCarrier.beginLightweightClient(
                        helperCh, PASS, false, 0, false, null, 0);
                ByteBuf extBuf = TransportCoreCarrier.encodeLightweightClientExt(helperCh.alloc(), state);
                extBytes = new byte[extBuf.readableBytes()];
                extBuf.readBytes(extBytes);
                extBuf.release();
            } finally {
                helperCh.finishAndReleaseAll();
            }

            byte[] userBytes = USER.getBytes(StandardCharsets.UTF_8);
            byte[] passBytes = PASS.getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream authPlusExt = new ByteArrayOutputStream();
            authPlusExt.write(1);
            authPlusExt.write(userBytes.length);
            authPlusExt.write(userBytes);
            authPlusExt.write(passBytes.length);
            authPlusExt.write(passBytes);
            authPlusExt.write(extBytes);
            byte[] frame = authPlusExt.toByteArray();

            // First connection with this nonce succeeds
            try (Socket s1 = new Socket("127.0.0.1", port)) {
                s1.setSoTimeout(3000);
                OutputStream out = s1.getOutputStream();
                InputStream in = s1.getInputStream();
                out.write(new byte[]{5, 2, (byte) 0x8c, 2});
                out.flush();
                byte[] m = readExact(in, 2);
                assertEquals((byte) 0x8c, m[1]);
                out.write(frame);
                out.flush();
                byte[] resp = readExact(in, 2 + 43); // RFC 1929 (2B) + LightweightAck (43B: 'R','X',ver,flags,level,mtu,ports,nonce,mac)
                assertEquals(0, resp[1], "First 0x8c auth with fresh nonce must succeed");
                assertEquals('R', resp[2], "Ack magic[0] must be 'R'");
                assertEquals('X', resp[3], "Ack magic[1] must be 'X'");
                assertEquals(1, resp[4], "Ack version must be 1");
                assertEquals(1, resp[5] & 1, "Ack flags must confirm LW_FLAG_COMPRESS");
            }

            // Second connection replaying the exact same nonce MUST fail closed (channel closed / EOF)
            try (Socket s2 = new Socket("127.0.0.1", port)) {
                s2.setSoTimeout(3000);
                OutputStream out = s2.getOutputStream();
                InputStream in = s2.getInputStream();
                out.write(new byte[]{5, 2, (byte) 0x8c, 2});
                out.flush();
                byte[] m = readExact(in, 2);
                assertEquals((byte) 0x8c, m[1]);
                out.write(frame);
                out.flush();
                // LightweightServerExtHandler rejects replay BEFORE flushing RFC 1929 SUCCESS + ack (zero oracle)
                int next = in.read();
                assertEquals(-1, next, "Replayed 0x8c nonce must be rejected and closed without sending SUCCESS/ack");
            }
        }
    }

    @Test
    void fastSecurityModeAndCoreVsSocksIdentityMismatchRejected() throws Exception {
        // 1. SecurityMode.NONE forbidden with enableEncryption=true
        TransportConfig tc = new TransportConfig();
        assertEquals(SecurityMode.FAST, tc.effectiveSecurityMode());
        tc.setEnableEncryption(true);
        assertThrows(IllegalArgumentException.class, () -> tc.setSecurityMode(SecurityMode.NONE));
        TransportConfig tc2 = new TransportConfig();
        tc2.setSecurityMode(SecurityMode.NONE);
        assertThrows(IllegalArgumentException.class, () -> tc2.setEnableEncryption(true));

        // 2. Core-vs-SOCKS identity mismatch: if Core authenticated as "alice", SOCKS5 password auth as "bob" MUST fail
        SocksUser alice = new SocksUser("alice");
        alice.setPassword("pwdA");
        SocksUser bob = new SocksUser("bob");
        bob.setPassword("pwdB");
        Authenticator twoUsers = new Authenticator() {
            @Override
            public SocksUser login(String u, String p) {
                if ("alice".equals(u) && "pwdA".equals(p)) return alice;
                if ("bob".equals(u) && "pwdB".equals(p)) return bob;
                return null;
            }
        };
        SocksConfig cfg = new SocksConfig(0);
        EmbeddedChannel ch = new EmbeddedChannel();
        ch.pipeline().addLast(io.netty.handler.codec.socksx.v5.Socks5PasswordAuthRequestDecoder.class.getSimpleName(),
                new io.netty.handler.codec.socksx.v5.Socks5PasswordAuthRequestDecoder());
        ch.pipeline().addLast(Socks5PasswordAuthRequestHandler.DEFAULT);
        try {
            ch.attr(SocksContext.SOCKS_SVR).set(new SocksProxyServer(cfg, twoUsers));
            ch.attr(TransportCoreCarrier.ATTR_CORE_AUTH_USER).set("alice");
            // Attempt SOCKS5 auth as "bob" despite Core identity being "alice"
            ch.pipeline().context(io.netty.handler.codec.socksx.v5.Socks5PasswordAuthRequestDecoder.class.getSimpleName())
                    .fireChannelRead(new DefaultSocks5PasswordAuthRequest("bob", "pwdB"));
            Object resp = ch.readOutbound();
            assertInstanceOf(Socks5PasswordAuthResponse.class, resp);
            assertEquals(Socks5PasswordAuthStatus.FAILURE, ((Socks5PasswordAuthResponse) resp).status(),
                    "Core-vs-SOCKS identity mismatch MUST be rejected fail-closed");
            assertFalse(ch.isOpen());
        } finally {
            SocksProxyServer s = ch.attr(SocksContext.SOCKS_SVR).get();
            if (s != null) {
                s.close();
            }
            ch.finishAndReleaseAll();
        }
    }

    @Test
    void businessInflateBudgetDecoupledFromEgressInflateBudget() {
        TransportCoreCarrier.NonceReplayCache replayCache = new TransportCoreCarrier.NonceReplayCache();
        byte[] secret = "budget-test-secret".getBytes(StandardCharsets.UTF_8);

        // 1. Small EgressConfig.maxInflateBytes (64 KiB) MUST NOT break business zlib with 4 MiB maxBusinessInflateBytes
        EmbeddedChannel client = new EmbeddedChannel();
        EmbeddedChannel server = new EmbeddedChannel();
        try {
            EgressConfig smallEgress = EgressConfig.serverAutoAll();
            smallEgress.setMaxInflateBytes(64 * 1024);
            EgressCodec.installClient(client.pipeline(), EgressCodec.ZLIB_STORED);
            EgressCodec.installServer(server.pipeline(), smallEgress, EgressCodec.InnerProtocol.TRANSPORT_CORE);

            TransportCoreCarrier.ClientOptions cOpts = new TransportCoreCarrier.ClientOptions(
                    "u", secret, false, true, 3, false, 0, false, null, 0, null, 5000, 4 * 1024 * 1024);
            TransportCoreCarrier.ServerOptions sOpts = new TransportCoreCarrier.ServerOptions(
                    null, peer -> secret, null, true, 1200, 3, replayCache, true, 5000, 4 * 1024 * 1024, false, false);
            client.pipeline().addLast(TransportCoreCarrier.HANDLER_NAME, TransportCoreCarrier.clientHandler(cOpts));
            server.pipeline().addLast(TransportCoreCarrier.HANDLER_NAME, TransportCoreCarrier.serverHandler(sOpts));

            // Complete handshake first, then stream 128 KiB compressible business payload
            pump(client, server);
            pump(server, client);
            byte[] largeCompressible = new byte[128 * 1024];
            Arrays.fill(largeCompressible, (byte) 'B');
            client.writeAndFlush(Unpooled.wrappedBuffer(largeCompressible));
            pump(client, server);
            byte[] received = drainInbound(server, largeCompressible.length);
            assertArrayEquals(largeCompressible, received,
                    "128 KiB business zlib stream must succeed even when EgressConfig.maxInflateBytes=64 KiB");
        } finally {
            client.finishAndReleaseAll();
            server.finishAndReleaseAll();
        }

        // 2. Small maxBusinessInflateBytes (4096) MUST reject high-ratio zlib bomb cleanly with 0 leaks
        EmbeddedChannel c2 = new EmbeddedChannel();
        EmbeddedChannel s2 = new EmbeddedChannel();
        try {
            TransportCoreCarrier.ClientOptions cOpts = new TransportCoreCarrier.ClientOptions(
                    "u", secret, false, true, 9, false, 0, false, null, 0, null, 5000, 4 * 1024 * 1024);
            TransportCoreCarrier.ServerOptions sOpts = new TransportCoreCarrier.ServerOptions(
                    null, peer -> secret, null, true, 1200, 3, replayCache, true, 5000, 4096, false, false);
            c2.pipeline().addLast(TransportCoreCarrier.HANDLER_NAME, TransportCoreCarrier.clientHandler(cOpts));
            s2.pipeline().addLast(TransportCoreCarrier.HANDLER_NAME, TransportCoreCarrier.serverHandler(sOpts));

            pump(c2, s2);
            pump(s2, c2);
            byte[] bomb = new byte[64 * 1024]; // 64 KiB zeros compresses to ~100 bytes
            c2.writeAndFlush(Unpooled.wrappedBuffer(bomb));
            assertThrows(Exception.class, () -> {
                pump(c2, s2);
                s2.checkException();
            }, "High-ratio zlib bomb exceeding maxBusinessInflateBytes must be rejected");
        } finally {
            c2.finishAndReleaseAll();
            s2.finishAndReleaseAll();
        }
    }

    private static void pump(EmbeddedChannel from, EmbeddedChannel to) {
        Object msg;
        while ((msg = from.readOutbound()) != null) {
            if (msg instanceof ByteBuf) {
                to.writeInbound(msg);
            } else {
                io.netty.util.ReferenceCountUtil.release(msg);
            }
        }
    }

    private static byte[] drainInbound(EmbeddedChannel ch, int expected) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(expected);
        Object msg;
        while ((msg = ch.readInbound()) != null) {
            ByteBuf buf = (ByteBuf) msg;
            try {
                byte[] chunk = new byte[buf.readableBytes()];
                buf.readBytes(chunk);
                out.write(chunk, 0, chunk.length);
            } finally {
                buf.release();
            }
        }
        return out.toByteArray();
    }

    @Test
    void rpcCapabilitiesCacheSingleFlightNegativeTtlAndNoTransientCaching() throws Exception {
        SocksRpcCapabilitiesCache cache = new SocksRpcCapabilitiesCache(60_000L, 1_000L);
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch insideLoader = new CountDownLatch(1);
        CountDownLatch releaseLoader = new CountDownLatch(1);
        SocksRpcCapabilities expected = SocksRpcCapabilities.udpRelayGroup(2, 64);

        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Future<SocksRpcCapabilities>> futures = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                futures.add(pool.submit(() -> {
                    startGate.await(5, TimeUnit.SECONDS);
                    return cache.get("tok-1", () -> {
                        calls.incrementAndGet();
                        insideLoader.countDown();
                        try {
                            releaseLoader.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return expected;
                    });
                }));
            }
            startGate.countDown();
            assertTrue(insideLoader.await(5, TimeUnit.SECONDS));
            releaseLoader.countDown();
            for (Future<SocksRpcCapabilities> f : futures) {
                assertSame(expected, f.get(5, TimeUnit.SECONDS));
            }
            assertEquals(1, calls.get(), "16 concurrent first-time queries must coalesce into 1 RPC");
            // Cached hit
            assertSame(expected, cache.get("tok-1", () -> {
                calls.incrementAndGet();
                return expected;
            }));
            assertEquals(1, calls.get());

            // Invalidation triggers re-query
            cache.invalidateAll();
            // Transient exception must NOT be cached
            assertThrows(IllegalStateException.class, () -> cache.get("tok-1", () -> {
                calls.incrementAndGet();
                throw new IllegalStateException("transient");
            }));
            assertEquals(2, calls.get());
            assertSame(expected, cache.get("tok-1", () -> {
                calls.incrementAndGet();
                return expected;
            }));
            assertEquals(3, calls.get());

            // Explicit UNSUPPORTED (EMPTY) cached for short negative TTL
            cache.invalidateAll();
            AtomicInteger negCalls = new AtomicInteger();
            assertSame(SocksRpcCapabilities.EMPTY, cache.get("tok-2", () -> {
                negCalls.incrementAndGet();
                return SocksRpcCapabilities.EMPTY;
            }));
            assertSame(SocksRpcCapabilities.EMPTY, cache.get("tok-2", () -> {
                negCalls.incrementAndGet();
                return SocksRpcCapabilities.EMPTY;
            }));
            assertEquals(1, negCalls.get(), "Explicit UNSUPPORTED should hit short negative cache");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void plainUdpPrioritizesRssRpcGroupZeroTcpControlSocketsAndReusesAcrossDestinations() throws Exception {
        int serverPort = freeTcpPort();
        SocksConfig serverCfg = new SocksConfig(serverPort);
        serverCfg.setDailyUdpFeatures(0); // plain UDP server
        AtomicInteger capCalls = new AtomicInteger();
        AtomicInteger openCalls = new AtomicInteger();

        try (DatagramSocket dst1 = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
             DatagramSocket dst2 = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
             SocksProxyServer server = new SocksProxyServer(serverCfg);
             WireSnifferProxy tcpSniffer = new WireSnifferProxy(new InetSocketAddress("127.0.0.1", serverPort))) {

            SocksRpcContract realLocal = new SocksProxyServerIntegrationTest.LocalRpcFacade(server);
            SocksRpcContract spyFacade = new SocksRpcContract() {
                @Override public boolean fakeEndpoint(String f, String r, String t) { return realLocal.fakeEndpoint(f, r, t); }
                @Override public void addWhiteList(java.net.InetAddress e, String t) { realLocal.addWhiteList(e, t); }
                @Override public List<java.net.InetAddress> resolveHost(java.net.InetAddress s, String h) { return realLocal.resolveHost(s, h); }
                @Override public SocksRpcCapabilities capabilities(String t) {
                    capCalls.incrementAndGet();
                    return realLocal.capabilities(t);
                }
                @Override public UdpRelayGroupOpenResult openUdpRelayGroup(UdpRelayGroupOpenRequest req, String t) {
                    openCalls.incrementAndGet();
                    return realLocal.openUdpRelayGroup(req, t);
                }
                @Override public boolean heartbeatUdpRelayGroup(String g, String t) { return realLocal.heartbeatUdpRelayGroup(g, t); }
                @Override public boolean closeUdpRelayGroup(String g, String t) { return realLocal.closeUdpRelayGroup(g, t); }
            };

            Map<String, String> gen1Params = Collections.singletonMap(UpstreamSupport.POOL_GENERATION, "gen-1");
            Map<String, String> gen2Params = Collections.singletonMap(UpstreamSupport.POOL_GENERATION, "gen-2");
            UpstreamSupport nextGen1 = new UpstreamSupport(
                    new AuthenticEndpoint(new InetSocketAddress("127.0.0.1", tcpSniffer.port), null, null, gen1Params), spyFacade);
            UpstreamSupport nextGen2 = new UpstreamSupport(
                    new AuthenticEndpoint(new InetSocketAddress("127.0.0.1", tcpSniffer.port), null, null, gen2Params), spyFacade);

            SocksConfig plainCfg = new SocksConfig();
            plainCfg.setDailyUdpFeatures(0);
            plainCfg.setUdpRelayControlMode(UdpRelayControlMode.AUTO);

            SocksUdpUpstream upDst1 = new SocksUdpUpstream((InetSocketAddress) dst1.getLocalSocketAddress(), plainCfg, nextGen1);
            SocksUdpUpstream upDst2 = new SocksUdpUpstream((InetSocketAddress) dst2.getLocalSocketAddress(), plainCfg, nextGen1);
            SocksUdpUpstream upGen2 = new SocksUdpUpstream((InetSocketAddress) dst1.getLocalSocketAddress(), plainCfg, nextGen2);

            LinkedBlockingQueue<Integer> replies = new LinkedBlockingQueue<>();
            Channel udpCh = Sockets.udpBootstrap(null, ch -> ch.pipeline().addLast(
                    new SimpleChannelInboundHandler<io.netty.channel.socket.DatagramPacket>() {
                        @Override
                        protected void channelRead0(ChannelHandlerContext ctx, io.netty.channel.socket.DatagramPacket p) {
                            UdpManager.socks5Decode(p.content());
                            replies.add(p.content().readInt());
                        }
                    })).bind(new InetSocketAddress("127.0.0.1", 0)).sync().channel();
            try {
                // First destination initializes group via RPC (1 capabilities + 1 open, 0 SOCKS5 TCP connections!)
                upDst1.initChannelAsync(udpCh).get(10, TimeUnit.SECONDS);
                // Second destination on same channel/generation/policy REUSES the exact same group (0 extra RPC!)
                upDst2.initChannelAsync(udpCh).get(10, TimeUnit.SECONDS);

                assertEquals(1, capCalls.get(), "Capabilities must be cached after first query");
                assertEquals(1, openCalls.get(), "Cross-destination plain UDP on same channel/policy/generation must reuse group (0 extra open)");
                assertEquals(0, tcpSniffer.connections.get(), "Plain UDP over RSS RPC Group must open ZERO SOCKS5 TCP control connections");
                assertEquals(upDst1.getUdpRelayAddress(udpCh), upDst2.getUdpRelayAddress(udpCh));

                // Verify echo to BOTH destinations works through the single shared RPC relay group
                for (Object[] pair : new Object[][]{{upDst1, dst1, 101}, {upDst2, dst2, 202}}) {
                    SocksUdpUpstream up = (SocksUdpUpstream) pair[0];
                    DatagramSocket dst = (DatagramSocket) pair[1];
                    int val = (Integer) pair[2];
                    dst.setSoTimeout(3000);
                    udpCh.writeAndFlush(new io.netty.channel.socket.DatagramPacket(
                            UdpManager.socks5Encode(udpCh.alloc().buffer(4).writeInt(val), (InetSocketAddress) dst.getLocalSocketAddress()),
                            up.getUdpRelayAddress(udpCh))).sync();
                    DatagramPacket pkt = new DatagramPacket(new byte[4], 4);
                    dst.receive(pkt);
                    dst.send(pkt);
                    assertEquals(Integer.valueOf(val), replies.poll(3, TimeUnit.SECONDS));
                }

                // Different routeGeneration MUST isolate into a new group (and hit cached capabilities = still 1 cap call!)
                upGen2.initChannelAsync(udpCh).get(10, TimeUnit.SECONDS);
                assertEquals(1, capCalls.get(), "Capabilities cache must stay hot across route generation open");
                assertEquals(2, openCalls.get(), "Different routeGeneration must open an isolated group");
                assertNotEquals(upDst1.getUdpRelayAddress(udpCh), upGen2.getUdpRelayAddress(udpCh));
                assertEquals(0, tcpSniffer.connections.get());

                // Standard third-party SOCKS5 upstream (isStandardSocks5=true) MUST bypass RPC and use SOCKS5 TCP UDP ASSOCIATE
                UpstreamSupport standardNext = new UpstreamSupport(
                        new AuthenticEndpoint(new InetSocketAddress("127.0.0.1", tcpSniffer.port), null, null), spyFacade);
                standardNext.setStandardSocks5(true);
                SocksUdpUpstream stdUp = new SocksUdpUpstream((InetSocketAddress) dst1.getLocalSocketAddress(), plainCfg, standardNext);
                stdUp.initChannelAsync(udpCh).get(10, TimeUnit.SECONDS);
                assertEquals(1, tcpSniffer.connections.get(), "Standard third-party SOCKS5 must still use TCP UDP ASSOCIATE");
                assertEquals(2, openCalls.get(), "Standard third-party SOCKS5 must NOT call openUdpRelayGroup");
            } finally {
                SocksRpcCapabilitiesCache.invalidateFacade(spyFacade);
                udpCh.close().sync();
            }
        }
    }

    @Test
    void udpRelayGroupManagerAtomicCapacityReservationAndIdempotency() throws Exception {
        SocksConfig cfg = new SocksConfig(new io.netty.channel.local.LocalAddress(UUID.randomUUID().toString()));
        cfg.setDailyUdpFeatures(0);
        try (SocksProxyServer server = new SocksProxyServer(cfg)) {
            UdpRelayGroupManager mgr = server.getUdpRelayGroupManager();
            mgr.maxGroups = 8;
            mgr.maxGroupsPerClient = 4;
            mgr.maxTotalRelays = 16;

            // 1. 32-thread concurrent open storm across 3 clients against global cap=8 & per-client cap=4
            ExecutorService pool = Executors.newFixedThreadPool(16);
            CountDownLatch gate = new CountDownLatch(1);
            List<Future<UdpRelayGroupOpenResult>> futures = new ArrayList<>();
            try {
                for (int i = 0; i < 32; i++) {
                    final String clientId = "client-" + (i % 3);
                    futures.add(pool.submit(() -> {
                        gate.await(5, TimeUnit.SECONDS);
                        UdpRelayGroupOpenRequest req = new UdpRelayGroupOpenRequest();
                        req.setClientId(clientId);
                        req.setClientRequestId(UUID.randomUUID().toString());
                        req.setInitialRelayCount(1);
                        req.setMinActiveRelays(1);
                        req.setMaxRelayCount(1);
                        return mgr.open(req);
                    }));
                }
                gate.countDown();

                List<String> opened = new ArrayList<>();
                Map<String, Integer> perClient = new HashMap<>();
                for (int i = 0; i < futures.size(); i++) {
                    UdpRelayGroupOpenResult res = futures.get(i).get(10, TimeUnit.SECONDS);
                    if (res.isSuccess()) {
                        opened.add(res.getGroupId());
                        String clientId = "client-" + (i % 3);
                        perClient.merge(clientId, 1, Integer::sum);
                    }
                }
                assertEquals(8, opened.size(), "Global maxGroups=8 must be enforced atomically under concurrency");
                assertEquals(8, mgr.reservedGroupCount());
                assertEquals(8, mgr.reservedRelayCount());
                for (Map.Entry<String, Integer> entry : perClient.entrySet()) {
                    assertTrue(entry.getValue() <= 4,
                            "Per-client cap=4 violated for " + entry.getKey() + ": " + entry.getValue());
                    assertEquals(entry.getValue().intValue(), mgr.clientGroupCount(entry.getKey()));
                }

                // Close all and verify exact zero rollback + zero-count cleanup
                for (String groupId : opened) {
                    assertTrue(mgr.close(groupId));
                }
                assertEquals(0, mgr.reservedGroupCount());
                assertEquals(0, mgr.reservedRelayCount());
                assertEquals(0, mgr.clientGroupCount("client-0"));
                assertEquals(0, mgr.clientGroupCount("client-1"));
                assertEquals(0, mgr.clientGroupCount("client-2"));

                // 2. Idempotent clientRequestId returns the exact same groupId without double-allocating
                String reqId = "idem-" + UUID.randomUUID();
                UdpRelayGroupOpenRequest req1 = new UdpRelayGroupOpenRequest();
                req1.setClientId("client-idem");
                req1.setClientRequestId(reqId);
                req1.setInitialRelayCount(1);
                req1.setMinActiveRelays(1);
                req1.setMaxRelayCount(1);
                UdpRelayGroupOpenResult r1 = mgr.open(req1);
                UdpRelayGroupOpenResult r2 = mgr.open(req1);
                assertTrue(r1.isSuccess() && r2.isSuccess());
                assertEquals(r1.getGroupId(), r2.getGroupId(), "Duplicate clientRequestId must return existing group");
                assertEquals(1, mgr.reservedGroupCount());
                assertEquals(1, mgr.clientGroupCount("client-idem"));
                assertTrue(mgr.close(r1.getGroupId()));
                assertEquals(0, mgr.reservedGroupCount());
            } finally {
                pool.shutdownNow();
            }
        }
    }

    public interface PingService {
        String echo(String v);
    }

    @Test
    void tcpWarmPoolDemandDrivenSingleSchedulerAndRpcPoolMinZero() throws Exception {
        // 1. TcpWarmPoolKey isolates handshakeMode & securityMode
        AuthenticEndpoint ep = new AuthenticEndpoint(new InetSocketAddress("127.0.0.1", 1080), USER, PASS);
        SocksConfig cfgLw = new SocksConfig();
        cfgLw.setHandshakeMode(HandshakeMode.LIGHTWEIGHT);
        cfgLw.setSecurityMode(SecurityMode.FAST);
        SocksConfig cfgCore = new SocksConfig();
        cfgCore.setHandshakeMode(HandshakeMode.CORE);
        cfgCore.setSecurityMode(SecurityMode.FAST);
        SocksConfig cfgNone = new SocksConfig();
        cfgNone.setHandshakeMode(HandshakeMode.LIGHTWEIGHT);
        cfgNone.setSecurityMode(SecurityMode.NONE);
        assertNotEquals(TcpWarmPoolKey.from(ep, cfgLw, null), TcpWarmPoolKey.from(ep, cfgCore, null));
        assertNotEquals(TcpWarmPoolKey.from(ep, cfgLw, null), TcpWarmPoolKey.from(ep, cfgNone, null));

        // 2. Demand-driven single shared scheduler & idle shrink to 0 past maxIdleMillis
        int serverPort = freeTcpPort();
        SocksConfig serverCfg = new SocksConfig(serverPort);
        serverCfg.setRssPrivateTransport(true);
        serverCfg.setHandshakeMode(HandshakeMode.LIGHTWEIGHT);
        try (SocksProxyServer remote = new SocksProxyServer(serverCfg, singleUserAuth())) {
            Socks5UpstreamPoolManager.INSTANCE.closeAll();
            SocksConfig warmCfg = new SocksConfig();
            warmCfg.setRssPrivateTransport(true);
            warmCfg.setHandshakeMode(HandshakeMode.LIGHTWEIGHT);
            warmCfg.setTcpWarmPoolEnabled(true);
            warmCfg.setTcpWarmPoolMinSize(1);
            warmCfg.setTcpWarmPoolMaxIdleMillis(1000);
            warmCfg.setTcpWarmPoolRefillIntervalMillis(500);
            UpstreamSupport up = new UpstreamSupport(
                    new AuthenticEndpoint(new InetSocketAddress("127.0.0.1", serverPort), USER, PASS), null);
            SocksTcpUpstream tcpUp = new SocksTcpUpstream(new InetSocketAddress("127.0.0.1", 80), warmCfg, up);

            // First borrow triggers demand-driven refill and starts single shared scheduler
            Socks5UpstreamPoolManager.INSTANCE.borrowWarmChannel(tcpUp);
            Socks5UpstreamPoolManager.TcpWarmPoolStats activeStats = Socks5UpstreamPoolManager.INSTANCE.tcpWarmPoolStats();
            assertEquals(1, activeStats.getPoolCount());
            assertTrue(activeStats.isSchedulerActive(), "Single shared maintenance scheduler must activate lazily");

            // Wait for 1 warmed channel to arrive
            long deadline = System.currentTimeMillis() + 4000;
            while (System.currentTimeMillis() < deadline
                    && Socks5UpstreamPoolManager.INSTANCE.tcpWarmPoolStats().getReadyChannels() < 1) {
                Thread.sleep(50);
            }
            assertTrue(Socks5UpstreamPoolManager.INSTANCE.tcpWarmPoolStats().getReadyChannels() >= 1);

            // Wait past maxIdleMillis + maintenance tick so idle pool shrinks back to 0 without re-dialing
            long shrinkDeadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < shrinkDeadline
                    && Socks5UpstreamPoolManager.INSTANCE.tcpWarmPoolStats().getReadyChannels() > 0) {
                Thread.sleep(100);
            }
            Socks5UpstreamPoolManager.TcpWarmPoolStats shrunk = Socks5UpstreamPoolManager.INSTANCE.tcpWarmPoolStats();
            assertEquals(0, shrunk.getReadyChannels(), "Idle warm pool must shrink to 0 past maxIdleMillis");
            assertTrue(shrunk.getUnusedExpired() >= 1);

            Socks5UpstreamPoolManager.INSTANCE.closeAll();
            assertFalse(Socks5UpstreamPoolManager.INSTANCE.tcpWarmPoolStats().isSchedulerActive(),
                    "Shared maintenance scheduler must cancel when all pools are closed");
        }

        // 3. RPC pool with minPoolSize = 0 works cleanly on demand
        int rpcPort = freeTcpPort();
        RpcServerConfig rpcServerCfg = new RpcServerConfig(new TcpServerConfig(rpcPort));
        PingService impl = v -> "pong:" + v;
        TransportServer rpcServer = Remoting.register(impl, rpcServerCfg);
        RpcClientConfig<PingService> rpcClientCfg = RpcClientConfig.poolMode(
                new InetSocketAddress("127.0.0.1", rpcPort), 0, 4);
        PingService facade = Remoting.createFacade(PingService.class, rpcClientCfg);
        try {
            assertEquals("pong:hello", facade.echo("hello"));
        } finally {
            if (facade instanceof AutoCloseable) {
                ((AutoCloseable) facade).close();
            }
            rpcServer.close();
        }
    }
}
