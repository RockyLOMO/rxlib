package org.rx.util.rss;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.BandwidthLimitConfig;
import org.rx.net.BandwidthLimiter;
import org.rx.net.Sockets;
import org.rx.net.socks.ShadowsocksConfig;
import org.rx.net.socks.ShadowsocksServer;
import org.rx.net.socks.UdpManager;
import org.rx.net.socks.encryption.CipherKind;
import org.rx.net.socks.encryption.ICrypto;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SsUserBandwidthIntegrationTest {
    private static final String CIPHER = CipherKind.AES_256_GCM.getCipherName();
    private static ServerBootstrap tcpSinkBootstrap;
    private static Channel tcpSinkChannel;
    private static int tcpSinkPort;
    private static ServerBootstrap tcpDownloadBootstrap;
    private static Channel tcpDownloadChannel;
    private static int tcpDownloadPort;
    private static Channel udpEchoChannel;
    private static int udpEchoPort;

    @BeforeAll
    static void startTargets() throws Exception {
        // TCP sink: reads until client EOF, then replies 4-byte total received length and closes.
        tcpSinkBootstrap = Sockets.serverBootstrap(ch -> ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
            int total;

            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                ByteBuf b = (ByteBuf) msg;
                total += b.readableBytes();
                b.release();
            }

            @Override
            public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
                if (evt instanceof io.netty.channel.socket.ChannelInputShutdownEvent) {
                    ByteBuf resp = ctx.alloc().buffer(4).writeInt(total);
                    ctx.writeAndFlush(resp).addListener(f -> ctx.close());
                    return;
                }
                super.userEventTriggered(ctx, evt);
            }
        }));
        tcpSinkBootstrap.childOption(io.netty.channel.ChannelOption.ALLOW_HALF_CLOSURE, true);
        tcpSinkChannel = tcpSinkBootstrap.bind(Sockets.newLoopbackEndpoint(0)).sync().channel();
        tcpSinkPort = ((InetSocketAddress) tcpSinkChannel.localAddress()).getPort();

        // TCP download server: reads 4-byte requested byte count, sends deterministic payload, and closes.
        tcpDownloadBootstrap = Sockets.serverBootstrap(ch -> ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
            final ByteBuf acc = Unpooled.buffer(4);

            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                ByteBuf b = (ByteBuf) msg;
                try {
                    acc.writeBytes(b);
                    if (acc.readableBytes() >= 4) {
                        int count = acc.readInt();
                        byte[] payload = new byte[count];
                        for (int i = 0; i < count; i++) {
                            payload[i] = (byte) (i & 0xFF);
                        }
                        ctx.writeAndFlush(Unpooled.wrappedBuffer(payload)).addListener(f -> ctx.close());
                    }
                } finally {
                    b.release();
                }
            }

            @Override
            public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                if (acc.refCnt() > 0) {
                    acc.release();
                }
                super.channelInactive(ctx);
            }
        }));
        tcpDownloadChannel = tcpDownloadBootstrap.bind(Sockets.newLoopbackEndpoint(0)).sync().channel();
        tcpDownloadPort = ((InetSocketAddress) tcpDownloadChannel.localAddress()).getPort();

        Bootstrap udpBootstrap = Sockets.udpBootstrap(null, ob -> ob.pipeline().addLast(new SimpleChannelInboundHandler<DatagramPacket>() {
            @Override
            protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket msg) {
                ctx.writeAndFlush(new DatagramPacket(msg.content().retain(), msg.sender()));
            }
        }));
        udpEchoChannel = udpBootstrap.bind(Sockets.newLoopbackEndpoint(0)).sync().channel();
        udpEchoPort = ((InetSocketAddress) udpEchoChannel.localAddress()).getPort();
    }

    @AfterAll
    static void stopTargets() {
        if (tcpSinkChannel != null) tcpSinkChannel.close();
        if (tcpSinkBootstrap != null) Sockets.closeBootstrap(tcpSinkBootstrap);
        if (tcpDownloadChannel != null) tcpDownloadChannel.close();
        if (tcpDownloadBootstrap != null) Sockets.closeBootstrap(tcpDownloadBootstrap);
        if (udpEchoChannel != null) udpEchoChannel.close();
    }

    @Test
    @Timeout(25)
    void ssSharedUserBudgetEnforcedAcrossMultipleTcpConnectionsAndHalfClosePreservesBytes() throws Exception {
        // 64 KiB/s upload, 64 KiB/s download (initial burst = 16 KiB)
        BandwidthLimiter userA = new BandwidthLimiter("userA", new BandwidthLimitConfig(64L, 64L));
        BandwidthLimiter userB = new BandwidthLimiter("userB", new BandwidthLimitConfig(0L, 0L));

        ShadowsocksConfig cfgA = new ShadowsocksConfig(Sockets.newLoopbackEndpoint(0), CIPHER, "pwdA");
        cfgA.setBandwidthLimiter(userA);
        ShadowsocksConfig cfgB = new ShadowsocksConfig(Sockets.newLoopbackEndpoint(0), CIPHER, "pwdB");
        cfgB.setBandwidthLimiter(userB);

        try (ShadowsocksServer svrA = new ShadowsocksServer(cfgA);
             ShadowsocksServer svrB = new ShadowsocksServer(cfgB)) {
            final int portA = boundTcpPort(svrA);
            final int portB = boundTcpPort(svrB);

            // 1) Two concurrent TCP upload connections sharing userA's 64 KiB/s budget (48 KiB each = 96 KiB total).
            // With 16 KiB burst, remaining 80 KiB requires >= 80/64 = 1.25s; must not double allowance across 2 conns.
            // Both clients half-close immediately after sending -> verifies zero lost bytes under paced half-close!
            ExecutorService pool = Executors.newFixedThreadPool(3);
            try {
                long t0 = System.nanoTime();
                Future<Integer> u1 = pool.submit(() -> ssUploadAndHalfClose(portA, "pwdA", 48 * 1024));
                Future<Integer> u2 = pool.submit(() -> ssUploadAndHalfClose(portA, "pwdA", 48 * 1024));
                // UserB runs unlimited concurrently and must finish rapidly without being throttled by userA
                Future<Long> bElapsed = pool.submit(() -> {
                    long bs = System.nanoTime();
                    assertEquals(64 * 1024, ssUploadAndHalfClose(portB, "pwdB", 64 * 1024));
                    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - bs);
                });

                assertEquals(48 * 1024, u1.get(15, TimeUnit.SECONDS).intValue());
                assertEquals(48 * 1024, u2.get(15, TimeUnit.SECONDS).intValue());
                long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
                long userBMs = bElapsed.get(10, TimeUnit.SECONDS);

                assertTrue(elapsedMs >= 950L, "Expected shared 64 KiB/s upload throttling (>=950ms for 96KiB), got " + elapsedMs + "ms");
                assertTrue(userBMs < 800L, "Unlimited userB should not be throttled by userA, took " + userBMs + "ms");
                assertEquals(96L * 1024L, userA.snapshot().admittedUploadBytes);
                assertEquals(0L, userB.snapshot().admittedUploadBytes);

                // 2) Download 80 KiB with backend closing right after write -> paced download must not truncate tail!
                long dl0 = System.nanoTime();
                byte[] downloaded = ssDownload(portA, "pwdA", 80 * 1024);
                long dlMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - dl0);
                assertEquals(80 * 1024, downloaded.length);
                for (int i = 0; i < downloaded.length; i++) {
                    assertEquals((byte) (i & 0xFF), downloaded[i], "Download byte mismatch at " + i);
                }
                // 80 KiB with 16 KiB burst at 64 KiB/s -> >= 64/64 = 1.0s
                assertTrue(dlMs >= 750L, "Expected 64 KiB/s download throttling (>=750ms), got " + dlMs + "ms");
                // 80 KiB download + two 4-byte sink replies from u1 and u2 = 81928 exact decrypted payload bytes
                assertEquals(80L * 1024L + 8L, userA.snapshot().admittedDownloadBytes);
                assertEquals(0L, userA.pendingTcpBytes());
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    @Timeout(30)
    void ssDownloadDrainsCompletelyAt40KiBpsWhenBackendClosesImmediatelyAndWithDedicatedCryptoGroup() throws Exception {
        // Reproduces real-VPS bench_bw: 40 KiB/s up/down, 160 KiB download where backend closes immediately after send
        BandwidthLimiter limiter = new BandwidthLimiter("bench_bw_repro", new BandwidthLimitConfig(40L, 40L));
        ShadowsocksConfig cfg = new ShadowsocksConfig(Sockets.newLoopbackEndpoint(0), CIPHER, "pwdBw40");
        cfg.setUseDedicatedCryptoGroup(true);
        cfg.setBandwidthLimiter(limiter);

        try (ShadowsocksServer svr = new ShadowsocksServer(cfg)) {
            int port = boundTcpPort(svr);
            long t0 = System.nanoTime();
            byte[] downloaded = ssDownload(port, "pwdBw40", 160 * 1024);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            assertEquals(160 * 1024, downloaded.length, "160 KiB download must not stall or truncate after backend EOF");
            for (int i = 0; i < downloaded.length; i++) {
                assertEquals((byte) (i & 0xFF), downloaded[i], "Byte mismatch at " + i);
            }
            // 160 KiB with 16 KiB burst at 40 KiB/s -> >= 144/40 = 3.6s (allow small scheduling margin)
            assertTrue(elapsedMs >= 2800L && elapsedMs < 15000L,
                    "Expected ~3.6s paced download at 40 KiB/s, got " + elapsedMs + "ms");
            assertEquals(0L, limiter.pendingTcpBytes());
            assertEquals(0, limiter.pendingTcpTasks());

            // Also verify 160 KiB upload + half-close completes cleanly at 40 KiB/s
            long ul0 = System.nanoTime();
            int uploaded = ssUploadAndHalfClose(port, "pwdBw40", 160 * 1024);
            long ulMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - ul0);
            assertEquals(160 * 1024, uploaded);
            assertTrue(ulMs >= 2800L && ulMs < 15000L,
                    "Expected ~3.6s paced upload at 40 KiB/s, got " + ulMs + "ms");
            assertEquals(0L, limiter.pendingTcpBytes());
        }
    }

    @Test
    @Timeout(15)
    void ssUdpExcessDroppedEarlyAndSharesBudgetWithTcp() throws Exception {
        // 16 KiB/s upload & download (initial burst = 16 KiB)
        BandwidthLimiter limiter = new BandwidthLimiter("udpUser", new BandwidthLimitConfig(16L, 16L));
        ShadowsocksConfig cfg = new ShadowsocksConfig(Sockets.newLoopbackEndpoint(0), CIPHER, "pwdUdp");
        cfg.setBandwidthLimiter(limiter);

        try (ShadowsocksServer svr = new ShadowsocksServer(cfg);
             DatagramSocket client = new DatagramSocket()) {
            client.setSoTimeout(300);
            int udpPort = boundUdpPort(svr);
            InetSocketAddress ssAddr = new InetSocketAddress("127.0.0.1", udpPort);

            // Burst 40 x 1 KiB UDP packets immediately (40 KiB > 16 KiB burst)
            int received = 0;
            byte[] payload = new byte[1024];
            Arrays.fill(payload, (byte) 0x5A);
            for (int i = 0; i < 40; i++) {
                ICrypto enc = ICrypto.get(CIPHER, "pwdUdp");
                enc.setForUdp(true);
                ByteBuf plain = Unpooled.buffer(1040);
                UdpManager.encode(plain, "127.0.0.1", udpEchoPort);
                plain.writeBytes(payload);
                ByteBuf cipherBuf = enc.encrypt(plain);
                byte[] wire = toBytes(cipherBuf);
                plain.release();
                client.send(new java.net.DatagramPacket(wire, wire.length, ssAddr));
            }
            byte[] rx = new byte[4096];
            while (true) {
                try {
                    java.net.DatagramPacket resp = new java.net.DatagramPacket(rx, rx.length);
                    client.receive(resp);
                    received++;
                } catch (SocketTimeoutException e) {
                    break;
                }
            }
            BandwidthLimiter.Snapshot snap = limiter.snapshot();
            assertTrue(received >= 12 && received <= 24, "Expected burst-bounded UDP echo count, got " + received);
            assertTrue(snap.droppedUploadPackets >= 16, "Expected excess UDP uploads dropped early, got " + snap.droppedUploadPackets);
            assertEquals(snap.admittedUploadBytes, (40L - snap.droppedUploadPackets) * 1024L);
        }
    }

    @Test
    @Timeout(20)
    void rssRuntimeDynamicBandwidthReloadWithoutListenerRestartAndRollback() throws Exception {
        int[] ports = freePorts(6);
        RssClientConf conf1 = baseConf(ports, null);
        RssRuntime runtime = null;
        try {
            runtime = new RssRuntime(ports[5], conf1);
            RssRuntime.ShadowServerRef ref1 = runtime.shadowServers.get("u1");
            assertNotNull(ref1);
            // Unlimited user allocates ZERO limiter!
            assertNull(ref1.server.getConfig().getBandwidthLimiter());
            assertTrue(runtime.userBandwidthLimiters.isEmpty());

            // 1) Hot reload: unlimited -> limited (128 KiB/s up, 256 KiB/s down) WITHOUT restarting listener!
            RssClientConf conf2 = baseConf(ports, new BandwidthLimitConfig(128L, 256L));
            runtime.reload(conf1, conf2);
            RssRuntime.ShadowServerRef ref2 = runtime.shadowServers.get("u1");
            assertSame(ref1, ref2, "Bandwidth change must not restart ShadowsocksServer listener");
            BandwidthLimiter limiter = ref2.server.getConfig().getBandwidthLimiter();
            assertNotNull(limiter);
            assertEquals(128L * 1024L, limiter.rateBytesPerSecond(BandwidthLimiter.Direction.UPLOAD));
            assertEquals(256L * 1024L, limiter.rateBytesPerSecond(BandwidthLimiter.Direction.DOWNLOAD));

            // 2) Hot reload failure rolls back and keeps previous limiter untouched!
            RssClientConf confBad = baseConf(ports, new BandwidthLimitConfig(512L, 1024L));
            runtime.reloadPostPublishHook = () -> {
                throw new IllegalStateException("injected failure");
            };
            RssRuntime rt = runtime;
            assertThrows(RuntimeException.class, () -> rt.reload(conf2, confBad));
            runtime.reloadPostPublishHook = null;
            assertSame(limiter, runtime.shadowServers.get("u1").server.getConfig().getBandwidthLimiter());
            assertEquals(128L * 1024L, limiter.rateBytesPerSecond(BandwidthLimiter.Direction.UPLOAD));

            // 3) Hot reload: limited -> unlimited (0/0) WITHOUT restarting listener; restores null fast path!
            RssClientConf conf3 = baseConf(ports, new BandwidthLimitConfig(0L, 0L));
            runtime.reload(conf2, conf3);
            assertSame(ref1, runtime.shadowServers.get("u1"));
            assertNull(runtime.shadowServers.get("u1").server.getConfig().getBandwidthLimiter());
            assertFalse(limiter.isEnabled());

            // 4) Negative bandwidth rejected during config validation
            RssClientConf confNeg = baseConf(ports, null);
            confNeg.shadowUsers.get(0).setBandwidth(new BandwidthLimitConfig(-10L, 100L));
            assertFalse(RssClient.normalizeAndValidateRssConfig(confNeg));
        } finally {
            if (runtime != null) {
                runtime.close();
            }
        }
    }

    private static int ssUploadAndHalfClose(int ssPort, String pwd, int totalBytes) throws Exception {
        try (Socket s = new Socket("127.0.0.1", ssPort)) {
            s.setSoTimeout(10_000);
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();

            ICrypto enc = ICrypto.get(CIPHER, pwd);
            enc.setForUdp(false);
            ICrypto dec = ICrypto.get(CIPHER, pwd);
            dec.setForUdp(false);

            ByteBuf header = Unpooled.buffer();
            UdpManager.encode(header, "127.0.0.1", tcpSinkPort);
            writeEncrypted(out, enc, header);

            byte[] chunk = new byte[8192];
            Arrays.fill(chunk, (byte) 0x42);
            int remaining = totalBytes;
            while (remaining > 0) {
                int n = Math.min(remaining, chunk.length);
                writeEncrypted(out, enc, Unpooled.wrappedBuffer(chunk, 0, n));
                remaining -= n;
            }
            out.flush();
            s.shutdownOutput();

            ByteArrayOutputStream plain = readAllDecrypted(in, dec);
            byte[] arr = plain.toByteArray();
            assertEquals(4, arr.length, "Expected 4-byte total count reply");
            return ((arr[0] & 0xFF) << 24) | ((arr[1] & 0xFF) << 16) | ((arr[2] & 0xFF) << 8) | (arr[3] & 0xFF);
        }
    }

    private static byte[] ssDownload(int ssPort, String pwd, int totalBytes) throws Exception {
        try (Socket s = new Socket("127.0.0.1", ssPort)) {
            s.setSoTimeout(10_000);
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();

            ICrypto enc = ICrypto.get(CIPHER, pwd);
            enc.setForUdp(false);
            ICrypto dec = ICrypto.get(CIPHER, pwd);
            dec.setForUdp(false);

            ByteBuf req = Unpooled.buffer();
            UdpManager.encode(req, "127.0.0.1", tcpDownloadPort);
            req.writeInt(totalBytes);
            writeEncrypted(out, enc, req);
            out.flush();

            return readAllDecrypted(in, dec).toByteArray();
        }
    }

    private static void writeEncrypted(OutputStream out, ICrypto crypto, ByteBuf plain) throws Exception {
        try {
            ByteBuf encrypted = crypto.encrypt(plain);
            byte[] wire = toBytes(encrypted);
            out.write(wire);
        } finally {
            plain.release();
        }
    }

    private static ByteArrayOutputStream readAllDecrypted(InputStream in, ICrypto crypto) throws Exception {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            ByteBuf enc = Unpooled.wrappedBuffer(buf, 0, n);
            ByteBuf dec = crypto.decrypt(enc);
            try {
                if (dec.isReadable()) {
                    byte[] tmp = new byte[dec.readableBytes()];
                    dec.readBytes(tmp);
                    result.write(tmp);
                }
            } finally {
                dec.release();
            }
        }
        return result;
    }

    private static byte[] toBytes(ByteBuf buf) {
        try {
            byte[] out = new byte[buf.readableBytes()];
            buf.readBytes(out);
            return out;
        } finally {
            buf.release();
        }
    }

    private static int boundTcpPort(ShadowsocksServer server) throws Exception {
        java.lang.reflect.Field f = ShadowsocksServer.class.getDeclaredField("tcpChannels");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.List<Channel> channels = (java.util.List<Channel>) f.get(server);
        return ((InetSocketAddress) channels.get(0).localAddress()).getPort();
    }

    private static int boundUdpPort(ShadowsocksServer server) throws Exception {
        java.lang.reflect.Field f = ShadowsocksServer.class.getDeclaredField("udpChannels");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.List<Channel> channels = (java.util.List<Channel>) f.get(server);
        return ((InetSocketAddress) channels.get(0).localAddress()).getPort();
    }

    private static int[] freePorts(int count) throws Exception {
        ServerSocket[] sockets = new ServerSocket[count];
        int[] ports = new int[count];
        try {
            for (int i = 0; i < count; i++) {
                sockets[i] = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
                ports[i] = sockets[i].getLocalPort();
            }
            return ports;
        } finally {
            for (ServerSocket socket : sockets) {
                if (socket != null) socket.close();
            }
        }
    }

    private static RssClientConf baseConf(int[] ports, BandwidthLimitConfig bandwidth) {
        RssClientConf conf = new RssClientConf();
        conf.socksPwd = "test-pwd";
        conf.socksBindPort = false;
        conf.shadowDnsPort = ports[0];
        conf.nameserver.setRegisterPort(ports[1]);
        conf.tcpCipherPorts = new int[0];
        conf.udpCipherPorts = new int[0];
        conf.rpcAutoWhiteListSeconds = 0;
        conf.publicIpEndpoints = new String[]{"http://127.0.0.1:1/ip"};

        RssClientConf.SocksServer std = new RssClientConf.SocksServer();
        std.id = "std-1";
        std.type = RssClientConf.UpstreamType.SOCKS5;
        std.weight = 10;
        std.endpoint = new AuthenticEndpoint(new InetSocketAddress("127.0.0.1", ports[3]), "u", "p");
        conf.socksServers = Collections.singletonList(std);

        ShadowUser user = new ShadowUser();
        user.setUsername("u1");
        user.setSocksUser("socks-u");
        user.setSsPort(ports[4]);
        user.setSsPwd("pwd-u1");
        RssTransport plain = new RssTransport();
        plain.profile = "plain";
        plain.udp = 0;
        plain.udpHop = false;
        user.transport = plain;
        user.setBandwidth(bandwidth);
        conf.shadowUsers = Collections.singletonList(user);
        assertTrue(RssClient.normalizeAndValidateRssConfig(conf));
        return conf;
    }
}
