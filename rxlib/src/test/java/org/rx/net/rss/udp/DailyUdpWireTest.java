package org.rx.net.rss.udp;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.socket.DatagramPacket;
import org.junit.jupiter.api.Test;
import org.rx.net.Sockets;
import org.rx.net.socks.*;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class DailyUdpWireTest {
    @Test
    void ipv4ChangesReturnPortAndKeepsTargetTuple() throws Exception {
        verifyHops(InetAddress.getByName("127.0.0.1"), DailyUdpWire.FEC);
    }

    @Test
    void ipv6ChangesReturnPortAndKeepsTargetTuple() throws Exception {
        verifyHops(InetAddress.getByName("::1"), DailyUdpWire.FEC);
    }

    @Test
    void negotiatedCompressionShrinksBothHopRepliesAndPreservesRawTarget() throws Exception {
        verifyHops(InetAddress.getByName("127.0.0.1"), DailyUdpWire.LZ4);
        verifyHops(InetAddress.getByName("::1"), DailyUdpWire.LZ4 | DailyUdpWire.FEC);
    }

    @Test
    void authenticatedUdpCombinationsHopAndEcho() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(DailyUdpWire.hasCipherKey(), "test deployment key required");
        for (int flags : new int[]{2, 3, 6, 7}) {
            verifyHops(InetAddress.getByName("127.0.0.1"), flags);
        }
    }

    @Test
    void gameAndWeakHopWithAuthenticatedFeedback() throws Exception {
        verifyHops(InetAddress.getByName("127.0.0.1"), DailyUdpWire.GAME);
        verifyHops(InetAddress.getByName("127.0.0.1"), DailyUdpWire.GAME | DailyUdpWire.FEC);
        verifyHops(InetAddress.getByName("::1"), DailyUdpWire.WEAK | DailyUdpWire.FEC | DailyUdpWire.LZ4);
        if (DailyUdpWire.hasCipherKey()) {
            verifyHops(InetAddress.getByName("::1"), DailyUdpWire.GAME | DailyUdpWire.AES);
        }
    }

    @Test
    void noHopSinglePortKeepsFecAndCompressionWithoutRotatingPort() throws Exception {
        InetAddress ip = InetAddress.getByName("127.0.0.1");
        SocksConfig config = new SocksConfig(new LocalAddress(UUID.randomUUID().toString()));
        config.setDailyUdpFeatures(31);
        int flags = DailyUdpWire.WEAK | DailyUdpWire.FEC | DailyUdpWire.LZ4;
        try (DatagramSocket target = new DatagramSocket(new InetSocketAddress(ip, 0));
             SocksProxyServer server = new SocksProxyServer(config)) {
            assertTrue(server.socksRpcCapabilities().has(SocksRpcCapabilities.SUPPORT_NO_HOP_UDP));
            target.setSoTimeout(5000);
            UdpRelayGroupOpenRequest req = new UdpRelayGroupOpenRequest();
            req.setMaxRelayCount(1);
            req.setDailyFeatures(flags);
            req.setProfileVersion(1);
            req.setClientSalt(UUID.randomUUID());
            UdpRelayGroupOpenResult result = server.openUdpRelayGroup(req, SocksRpcContract.rpcToken());
            assertTrue(result.isSuccess(), result.getErrorCode());
            assertEquals(1, result.getRelays().size());
            InetSocketAddress primary = new InetSocketAddress(ip, result.getRelays().get(0).getRelayAddress().getPort());
            AtomicLong clock = new AtomicLong();
            BlockingQueue<Integer> wirePorts = new LinkedBlockingQueue<>(), delivered = new LinkedBlockingQueue<>();
            Channel client = Sockets.udpBootstrap(null, ch -> {
                ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                    @Override public void channelRead(ChannelHandlerContext ctx, Object msg) {
                        DatagramPacket pkt = (DatagramPacket) msg;
                        wirePorts.add(pkt.sender().getPort());
                        ctx.fireChannelRead(msg);
                    }
                });
                ch.pipeline().addLast(new DailyUdpWire(result.getToken(), primary, primary, null, clock::get, flags,
                        req.getClientSalt(), result.getAuthSecret()));
                ch.pipeline().addLast(new SimpleChannelInboundHandler<DatagramPacket>() {
                    @Override protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) {
                        ByteBuf b = packet.content();
                        UdpManager.socks5Decode(b);
                        delivered.add(b.readableBytes() == 1200 ? b.readInt() : -1);
                    }
                });
            }).bind(new InetSocketAddress(ip, 0)).sync().channel();
            try {
                for (int i = 0; i < 4; i++) {
                    clock.set(i * DailyUdpWire.HOP_NANOS);
                    int value = 1200 << 16 | i;
                    ByteBuf b = UdpManager.socks5Encode(client.alloc().buffer(1200).writeInt(value).writeZero(1196),
                            (InetSocketAddress) target.getLocalSocketAddress());
                    client.writeAndFlush(new DatagramPacket(b, primary)).sync();
                    java.net.DatagramPacket received = new java.net.DatagramPacket(new byte[1200], 1200);
                    target.receive(received);
                    assertEquals(primary.getPort(), received.getPort());
                    target.send(received);
                    assertEquals(Integer.valueOf(value), delivered.poll(5, TimeUnit.SECONDS));
                    Integer replyPort = wirePorts.poll(1, TimeUnit.SECONDS);
                    assertEquals(Integer.valueOf(primary.getPort()), replyPort);
                }
            } finally {
                client.close().sync();
            }
        }
    }

    @Test
    void compressibleOversizedFitsSinglePacketWhileIncompressibleFragmentsWithinMtuAcrossProfiles() throws Exception {
        for (int flags : new int[]{
                DailyUdpWire.FEC | DailyUdpWire.LZ4,
                DailyUdpWire.WEAK | DailyUdpWire.FEC | DailyUdpWire.LZ4,
                DailyUdpWire.GAME | DailyUdpWire.LZ4,
                DailyUdpWire.GAME}) {
            verifyMtuAndFragmentation(InetAddress.getByName("127.0.0.1"), flags, 1300, 1400);
        }
        verifyMtuAndFragmentation(InetAddress.getByName("::1"), DailyUdpWire.WEAK | DailyUdpWire.FEC | DailyUdpWire.LZ4, 1280, 1300);
    }

    private void verifyMtuAndFragmentation(InetAddress ip, int flags, int clientMtu, int serverMtu) throws Exception {
        int effectiveMtu = Math.min(clientMtu, serverMtu);
        boolean lz4 = (flags & DailyUdpWire.LZ4) != 0;
        boolean profiled = (flags & (DailyUdpWire.GAME | DailyUdpWire.WEAK)) != 0;
        SocksConfig config = new SocksConfig(new LocalAddress(UUID.randomUUID().toString()));
        config.setDailyUdpFeatures(31);
        config.setDailyUdpMtu(serverMtu);
        try (DatagramSocket target = new DatagramSocket(new InetSocketAddress(ip, 0));
             SocksProxyServer server = new SocksProxyServer(config)) {
            assertTrue(server.socksRpcCapabilities().has(SocksRpcCapabilities.SUPPORT_UDP_FRAG));
            target.setSoTimeout(5000);
            UdpRelayGroupOpenRequest req = new UdpRelayGroupOpenRequest();
            req.setMaxRelayCount(1);
            req.setDailyFeatures(flags);
            if (profiled) req.setProfileVersion(1);
            req.setClientSalt(UUID.randomUUID());
            req.setUdpFragSupported(true);
            req.setUdpMtu(clientMtu);
            UdpRelayGroupOpenResult result = server.openUdpRelayGroup(req, SocksRpcContract.rpcToken());
            assertTrue(result.isSuccess(), result.getErrorCode());
            assertTrue(result.isUdpFragEnabled());
            assertEquals(effectiveMtu, result.getUdpMtu());
            assertNotNull(result.getAuthSecret());

            InetSocketAddress primary = new InetSocketAddress(ip, result.getRelays().get(0).getRelayAddress().getPort());
            InetSocketAddress targetAddr = (InetSocketAddress) target.getLocalSocketAddress();
            AtomicLong maxTxWire = new AtomicLong(), txPacketCount = new AtomicLong();
            BlockingQueue<byte[]> delivered = new LinkedBlockingQueue<>();
            Channel client = Sockets.udpBootstrap(null, ch -> {
                ch.pipeline().addLast(new io.netty.channel.ChannelOutboundHandlerAdapter() {
                    @Override
                    public void write(ChannelHandlerContext ctx, Object msg, io.netty.channel.ChannelPromise promise) throws Exception {
                        if (msg instanceof DatagramPacket && primary.equals(((DatagramPacket) msg).recipient())) {
                            int bytes = ((DatagramPacket) msg).content().readableBytes();
                            txPacketCount.incrementAndGet();
                            maxTxWire.accumulateAndGet(bytes, Math::max);
                        }
                        super.write(ctx, msg, promise);
                    }
                });
                ch.pipeline().addLast(DailyUdpWire.client(result.getToken(), primary, primary, result.getUdpMtu(),
                        flags, req.getClientSalt(), result.getAuthSecret(), true));
                ch.pipeline().addLast(new SimpleChannelInboundHandler<DatagramPacket>() {
                    @Override protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) {
                        ByteBuf b = packet.content();
                        UdpManager.socks5Decode(b);
                        byte[] out = new byte[b.readableBytes()];
                        b.readBytes(out);
                        delivered.add(out);
                    }
                });
            }).bind(new InetSocketAddress(ip, 0)).sync().channel();

            try {
                // 1. Small packet: exact wire compatibility, zero extra fragment header bytes.
                txPacketCount.set(0);
                maxTxWire.set(0);
                byte[] small = new byte[]{1, 2, 3, 4, 5, 6, 7, 8};
                sendAndAssertEcho(client, primary, target, targetAddr, small, delivered, effectiveMtu, maxTxWire);
                assertEquals(1L, txPacketCount.get());

                // 2. Compressible 4096B packet under LZ4: shrinks below MTU and sends WITHOUT fragmentation!
                if (lz4) {
                    txPacketCount.set(0);
                    maxTxWire.set(0);
                    byte[] compressible = new byte[4096];
                    java.util.Arrays.fill(compressible, (byte) 'A');
                    sendAndAssertEcho(client, primary, target, targetAddr, compressible, delivered, effectiveMtu, maxTxWire);
                    assertEquals(1L, txPacketCount.get(), "compressible 4096B datagram must not fragment");
                    assertTrue(maxTxWire.get() < 250, "compressed wire bytes=" + maxTxWire.get());
                }

                // 3. MTU boundary (-1, ==, +1) and incompressible 1400B, 1472B, 4096B, 32768B payloads.
                int headerLen = ip.getAddress().length == 16 ? 22 : 10;
                int wireOverhead = 20 + (((flags & (DailyUdpWire.GAME | DailyUdpWire.WEAK)) != 0) ? 24 : 0)
                        + (((flags & DailyUdpWire.FEC) != 0) ? 8 : 5);
                int boundaryRaw = effectiveMtu - wireOverhead - headerLen;
                java.util.Random rnd = new java.util.Random(42L + flags);
                for (int size : new int[]{boundaryRaw - 1, boundaryRaw, boundaryRaw + 1, 1400, 1472, 4096, 32768}) {
                    maxTxWire.set(0);
                    byte[] randomPayload = new byte[size];
                    rnd.nextBytes(randomPayload);
                    sendAndAssertEcho(client, primary, target, targetAddr, randomPayload, delivered, effectiveMtu, maxTxWire);
                }

                // 4. Unresolved domain header with incompressible 2048B payload.
                InetSocketAddress domainTarget = InetSocketAddress.createUnresolved(
                        ip.getAddress().length == 16 ? "::1" : "127.0.0.1", target.getLocalPort());
                byte[] domainPayload = new byte[2048];
                rnd.nextBytes(domainPayload);
                maxTxWire.set(0);
                sendAndAssertEcho(client, primary, target, domainTarget, domainPayload, delivered, effectiveMtu, maxTxWire);
            } finally {
                client.close().sync();
            }
        }
    }

    private static void sendAndAssertEcho(Channel client, InetSocketAddress primary, DatagramSocket target,
                                          InetSocketAddress dstHeader, byte[] payload,
                                          BlockingQueue<byte[]> delivered, int effectiveMtu,
                                          AtomicLong maxTxWire) throws Exception {
        ByteBuf encoded = UdpManager.socks5Encode(
                client.alloc().buffer(payload.length).writeBytes(payload), dstHeader);
        client.writeAndFlush(new DatagramPacket(encoded, primary)).sync();
        assertTrue(maxTxWire.get() <= effectiveMtu,
                "TX wire datagram " + maxTxWire.get() + " exceeded effectiveMtu " + effectiveMtu);
        byte[] recvBuf = new byte[65535];
        java.net.DatagramPacket received = new java.net.DatagramPacket(recvBuf, recvBuf.length);
        target.receive(received);
        assertEquals(payload.length, received.getLength());
        byte[] targetSaw = java.util.Arrays.copyOf(received.getData(), received.getLength());
        assertArrayEquals(payload, targetSaw);
        target.send(new java.net.DatagramPacket(targetSaw, targetSaw.length, received.getSocketAddress()));
        byte[] echoed = delivered.poll(5, TimeUnit.SECONDS);
        assertNotNull(echoed, "timed out waiting for UDP echo of size " + payload.length);
        assertArrayEquals(payload, echoed);
        assertNull(delivered.poll(20, TimeUnit.MILLISECONDS), "must deliver reassembled datagram only once");
    }

    @Test
    void fragmenterHandlesOutOfOrderDuplicatesExpiryTamperingBudgetAndFecRecovery() {
        UUID token = UUID.randomUUID();
        byte[] secret = new byte[32];
        new java.security.SecureRandom().nextBytes(secret);
        AtomicLong globalBudget = new AtomicLong();
        long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(750);
        io.netty.buffer.UnpooledByteBufAllocator alloc = io.netty.buffer.UnpooledByteBufAllocator.DEFAULT;

        // 1. Out-of-order, duplicate, late-duplicate, tampered HMAC, and missing-fragment expiry.
        try (DailyUdpFragmenter tx = new DailyUdpFragmenter(token, DailyUdpWire.FEC, false, secret,
                globalBudget, 64 * 1024L, timeoutNanos);
             DailyUdpFragmenter rx = new DailyUdpFragmenter(token, DailyUdpWire.FEC, true, secret,
                     globalBudget, 64 * 1024L, timeoutNanos)) {
            byte[] data = new byte[3000];
            new java.util.Random(7).nextBytes(data);
            java.util.List<ByteBuf> frags = new java.util.ArrayList<>();
            ByteBuf src = io.netty.buffer.Unpooled.wrappedBuffer(data);
            tx.fragment(alloc, src, 1200, (f, last) -> frags.add(f));
            assertEquals(3, frags.size());

            // Tampered fragment must fail HMAC before allocating reassembly state.
            ByteBuf tampered = frags.get(0).copy();
            tampered.setByte(tampered.writerIndex() - 1, tampered.getByte(tampered.writerIndex() - 1) ^ 1);
            assertNull(rx.accept(tampered, alloc, 1_000L));
            assertEquals(1, rx.rxInvalid);
            assertEquals(0, rx.bufferedBytes());
            assertEquals(0L, globalBudget.get());

            // Reverse order + duplicate middle fragment -> single exact reassembly.
            assertNull(rx.accept(frags.get(2), alloc, 2_000L));
            assertNull(rx.accept(frags.get(1).retainedDuplicate(), alloc, 2_000L));
            assertNull(rx.accept(frags.get(1), alloc, 2_000L)); // duplicate
            assertEquals(1, rx.rxDuplicates);
            ByteBuf done = rx.accept(frags.get(0).retainedDuplicate(), alloc, 2_000L);
            assertNotNull(done);
            try {
                byte[] actual = new byte[done.readableBytes()];
                done.readBytes(actual);
                assertArrayEquals(data, actual);
            } finally {
                done.release();
            }
            assertEquals(0, rx.bufferedBytes());
            assertEquals(0L, globalBudget.get());

            // Late duplicate after completion must be ignored without buffering.
            assertNull(rx.accept(frags.get(0), alloc, 3_000L));
            assertEquals(2, rx.rxDuplicates);
            assertEquals(0, rx.bufferedBytes());

            // Incomplete fragment group expires cleanly at 750ms and releases budget.
            java.util.List<ByteBuf> dropped = new java.util.ArrayList<>();
            tx.fragment(alloc, src, 1200, (f, last) -> dropped.add(f));
            assertNull(rx.accept(dropped.get(0), alloc, 10_000L));
            dropped.get(1).release();
            dropped.get(2).release();
            assertTrue(rx.bufferedBytes() > 0);
            assertTrue(rx.nextDelayMillis(10_000L) > 0);
            rx.expire(10_000L + timeoutNanos);
            assertEquals(1, rx.rxTimeouts);
            assertEquals(0, rx.bufferedBytes());
            assertEquals(0L, globalBudget.get());
            assertEquals(-1L, rx.nextDelayMillis(10_000L + timeoutNanos));

            // Global budget exhaustion rejects excess fragments and recovers after close.
            try (DailyUdpFragmenter tinyRx = new DailyUdpFragmenter(token, DailyUdpWire.FEC, true, secret,
                    globalBudget, 1500L, timeoutNanos)) {
                java.util.List<ByteBuf> over = new java.util.ArrayList<>();
                tx.fragment(alloc, src, 1200, (f, last) -> over.add(f));
                assertNull(tinyRx.accept(over.get(0), alloc, 20_000L));
                assertNull(tinyRx.accept(over.get(1), alloc, 20_000L));
                over.get(2).release();
                assertEquals(1, tinyRx.rxBudgetRejected);
            }
            assertEquals(0L, globalBudget.get());
        }

        // 2. FEC recovers a lost fragment of a 4096B incompressible datagram.
        AtomicLong clock = new AtomicLong(1_000_000L);
        try (UdpDailyFec fecTx = new UdpDailyFec(alloc, clock::get, false, 20);
             UdpDailyFec fecRx = new UdpDailyFec(alloc, clock::get, false, 20);
             DailyUdpFragmenter fragTx = new DailyUdpFragmenter(token, DailyUdpWire.FEC, false, secret);
             DailyUdpFragmenter fragRx = new DailyUdpFragmenter(token, DailyUdpWire.FEC, true, secret)) {
            // Enable parity on fecTx via one loss report after warmup.
            ByteBuf warmup = io.netty.buffer.Unpooled.buffer(16).writeZero(16);
            try {
                for (int i = 0; i < 40; i++) {
                    final int seq = i;
                    fecTx.write(warmup, f -> {
                        try {
                            if (seq < 10 || seq >= 14) fecRx.read(f, ByteBuf::release, r -> {
                                try { fecTx.read(r, ByteBuf::release, ByteBuf::release); } finally { r.release(); }
                            });
                        } finally {
                            f.release();
                        }
                    });
                }
            } finally {
                warmup.release();
            }
            byte[] large = new byte[4096];
            new java.util.Random(99).nextBytes(large);
            java.util.ArrayDeque<ByteBuf> wire = new java.util.ArrayDeque<>();
            fragTx.fragment(alloc, io.netty.buffer.Unpooled.wrappedBuffer(large), 1200, (frag, last) -> {
                try {
                    fecTx.write(frag, wire::add);
                } finally {
                    frag.release();
                }
            });
            assertTrue(fecTx.paritySent >= 1, "4 fragments with K=4 must emit parity");
            java.util.concurrent.atomic.AtomicReference<byte[]> recoveredDatagram = new java.util.concurrent.atomic.AtomicReference<>();
            int idx = 0;
            while (!wire.isEmpty()) {
                ByteBuf f = wire.remove();
                try {
                    if (idx++ == 1) {
                        continue; // Drop fragment #1; FEC parity must recover it!
                    }
                    fecRx.read(f, inner -> {
                        ByteBuf full = fragRx.accept(inner, alloc, clock.get());
                        if (full != null) {
                            try {
                                byte[] bytes = new byte[full.readableBytes()];
                                full.readBytes(bytes);
                                recoveredDatagram.set(bytes);
                            } finally {
                                full.release();
                            }
                        }
                    }, ByteBuf::release);
                } finally {
                    f.release();
                }
            }
            assertEquals(1L, fecRx.recoveredPackets());
            assertArrayEquals(large, recoveredDatagram.get());
        }

        // 3. Legacy unnegotiated wire fails promise on oversized write instead of silently claiming delivery.
        InetSocketAddress peer = new InetSocketAddress("127.0.0.1", 19999);
        io.netty.channel.embedded.EmbeddedChannel legacy = new io.netty.channel.embedded.EmbeddedChannel(
                DailyUdpWire.client(token.toString(), peer, peer, 1300, DailyUdpWire.FEC, null, null, false));
        try {
            byte[] oversized = new byte[1400];
            io.netty.channel.ChannelFuture future = legacy.writeAndFlush(
                    new DatagramPacket(io.netty.buffer.Unpooled.wrappedBuffer(oversized), peer));
            assertFalse(future.isSuccess());
            assertNull(legacy.readOutbound());
        } finally {
            legacy.finishAndReleaseAll();
        }
    }

    private void verifyHops(InetAddress ip, int flags) throws Exception {
        boolean lz4 = (flags & DailyUdpWire.LZ4) != 0;
        SocksConfig config = new SocksConfig(new LocalAddress(UUID.randomUUID().toString()));
        config.setDailyUdpFeatures(31);
        String redirectedRange = System.getenv("RSS_TEST_REDIRECTED_RANGE");
        if (redirectedRange != null) {
            config.setDailyPortRange(redirectedRange);
            config.setRedirectUdpHops(true);
        }
        try (DatagramSocket target = new DatagramSocket(new InetSocketAddress(ip, 0));
             SocksProxyServer server = new SocksProxyServer(config)) {
            target.setSoTimeout(5000);
            UdpRelayGroupOpenRequest req = new UdpRelayGroupOpenRequest();
            req.setMaxRelayCount(2);
            req.setDailyFeatures(flags);
            if ((flags & (DailyUdpWire.GAME | DailyUdpWire.WEAK)) != 0) req.setProfileVersion(1);
            req.setClientSalt(UUID.randomUUID());
            UdpRelayGroupOpenResult result = server.openUdpRelayGroup(req, SocksRpcContract.rpcToken());
            assertTrue(result.isSuccess(), result.getErrorCode());
            InetSocketAddress primary = new InetSocketAddress(ip, result.getRelays().get(0).getRelayAddress().getPort());
            InetSocketAddress alias = new InetSocketAddress(ip, result.getRelays().get(1).getRelayAddress().getPort());
            AtomicLong clock = new AtomicLong();
            BlockingQueue<Integer> wirePorts = new LinkedBlockingQueue<>(), delivered = new LinkedBlockingQueue<>();
            BlockingQueue<Integer> wireSizes = new LinkedBlockingQueue<>();
            Channel client = Sockets.udpBootstrap(null, ch -> {
                ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                    private int generation = -1;
                    @Override public void channelRead(ChannelHandlerContext ctx, Object msg) {
                        DatagramPacket pkt = (DatagramPacket) msg;
                        int hop = pkt.content().getInt(pkt.content().readerIndex() + 16);
                        if (hop > generation) {
                            generation = hop;
                            wirePorts.add(pkt.sender().getPort());
                            wireSizes.add(pkt.content().readableBytes());
                        }
                        ctx.fireChannelRead(msg);
                    }
                });
                ch.pipeline().addLast(new DailyUdpWire(result.getToken(), primary, alias, null, clock::get, flags, req.getClientSalt(),
                        (flags & DailyUdpWire.AES) != 0 ? DailyUdpCipher.configuredKey() : result.getAuthSecret()));
                ch.pipeline().addLast(new SimpleChannelInboundHandler<DatagramPacket>() {
                    @Override protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) {
                        ByteBuf b = packet.content();
                        UdpManager.socks5Decode(b);
                        delivered.add(b.readableBytes() == 1200 ? b.readInt() : -1);
                    }
                });
            }).bind(new InetSocketAddress(ip, 0)).sync().channel();
            try {
                for (int i = 0; i < 3; i++) {
                    clock.set(i * DailyUdpWire.HOP_NANOS);
                    int value = 1200 << 16 | i; // A raw target payload beginning with a plausible LZ4 length.
                    ByteBuf b = UdpManager.socks5Encode(client.alloc().buffer(1200).writeInt(value).writeZero(1196),
                            (InetSocketAddress) target.getLocalSocketAddress());
                    client.writeAndFlush(new DatagramPacket(b, primary)).sync();
                    java.net.DatagramPacket received = new java.net.DatagramPacket(new byte[1200], 1200);
                    target.receive(received);
                    assertEquals(primary.getPort(), received.getPort());
                    assertEquals(1200, received.getLength());
                    target.send(received);
                    assertEquals(Integer.valueOf(value), delivered.poll(5, TimeUnit.SECONDS));
                    assertEquals(Integer.valueOf((i & 1) == 0 ? primary.getPort() : alias.getPort()),
                            wirePorts.poll(1, TimeUnit.SECONDS));
                    int wireSize = wireSizes.remove();
                    assertTrue(lz4 ? wireSize < 100 : wireSize > 1200, "wire bytes=" + wireSize);
                }
            } finally {
                client.close().sync();
            }
        }
    }
}
