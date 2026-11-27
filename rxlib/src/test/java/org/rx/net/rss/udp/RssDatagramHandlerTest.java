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

class RssDatagramHandlerTest {
    @Test
    void plainAesEnforcesExactBudgetWithoutFragmenting() {
        InetSocketAddress peer = new InetSocketAddress("127.0.0.1", 19001);
        int mtu = 256;
        int limit = mtu - RssDatagramHandler.HEADER_BYTES - RssDatagramCipher.OVERHEAD;
        RssDatagramHandler wire = new RssDatagramHandler(UUID.randomUUID().toString(), peer, peer, null,
                System::nanoTime, RssDatagramHandler.AES, UUID.randomUUID(), new byte[32], mtu, true);
        io.netty.channel.embedded.EmbeddedChannel channel = new io.netty.channel.embedded.EmbeddedChannel(wire);
        try {
            for (int length = limit - 1; length <= limit + 1; length++) {
                ByteBuf input = channel.alloc().buffer(length).writeZero(length);
                io.netty.channel.ChannelFuture write = channel.writeAndFlush(new DatagramPacket(input, peer));
                DatagramPacket record = channel.readOutbound();
                if (length > limit) {
                    assertFalse(write.isSuccess());
                    assertNull(record);
                } else {
                    assertTrue(write.isSuccess());
                    try {
                        assertEquals(length + RssDatagramHandler.HEADER_BYTES + RssDatagramCipher.OVERHEAD, record.content().readableBytes());
                    } finally {
                        record.release();
                    }
                }
                assertEquals(0, input.refCnt());
            }
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void ipv4ChangesReturnPortAndKeepsTargetTuple() throws Exception {
        verifyHops(InetAddress.getByName("127.0.0.1"), RssDatagramHandler.FEC);
    }

    @Test
    void ipv6ChangesReturnPortAndKeepsTargetTuple() throws Exception {
        verifyHops(InetAddress.getByName("::1"), RssDatagramHandler.FEC);
    }

    @Test
    void negotiatedCompressionShrinksBothHopRepliesAndPreservesRawTarget() throws Exception {
        verifyHops(InetAddress.getByName("127.0.0.1"), RssDatagramHandler.LZ4);
        verifyHops(InetAddress.getByName("::1"), RssDatagramHandler.LZ4 | RssDatagramHandler.FEC);
    }

    @Test
    void authenticatedUdpCombinationsHopAndEcho() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(RssDatagramHandler.hasCipherKey(), "test deployment key required");
        for (int flags : new int[]{2, 3, 6, 7}) {
            verifyHops(InetAddress.getByName("127.0.0.1"), flags);
        }
    }

    @Test
    void gameAndWeakHopWithAuthenticatedFeedback() throws Exception {
        verifyHops(InetAddress.getByName("127.0.0.1"), RssDatagramHandler.GAME);
        assertThrows(IllegalArgumentException.class, () -> RssDatagramHandler.server(UUID.randomUUID().toString(),
                address -> false, RssDatagramHandler.GAME | RssDatagramHandler.FEC, null, new byte[32]));
        verifyHops(InetAddress.getByName("::1"), RssDatagramHandler.WEAK | RssDatagramHandler.FEC | RssDatagramHandler.LZ4);
        if (RssDatagramHandler.hasCipherKey()) {
            verifyHops(InetAddress.getByName("::1"), RssDatagramHandler.GAME | RssDatagramHandler.AES);
        }
    }

    @Test
    void noHopSinglePortKeepsFecAndCompressionWithoutRotatingPort() throws Exception {
        InetAddress ip = InetAddress.getByName("127.0.0.1");
        SocksConfig config = new SocksConfig(new LocalAddress(UUID.randomUUID().toString()));
        config.setDailyUdpFeatures(31);
        int flags = RssDatagramHandler.WEAK | RssDatagramHandler.FEC | RssDatagramHandler.LZ4;
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
                ch.pipeline().addLast(new RssDatagramHandler(result.getToken(), primary, primary, null, clock::get, flags,
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
                    clock.set(i * RssDatagramHandler.HOP_NANOS);
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
                RssDatagramHandler.FEC | RssDatagramHandler.LZ4,
                RssDatagramHandler.WEAK | RssDatagramHandler.FEC | RssDatagramHandler.LZ4,
                RssDatagramHandler.GAME | RssDatagramHandler.LZ4,
                RssDatagramHandler.GAME}) {
            verifyMtuAndFragmentation(InetAddress.getByName("127.0.0.1"), flags, 1300, 1400);
        }
        verifyMtuAndFragmentation(InetAddress.getByName("::1"), RssDatagramHandler.WEAK | RssDatagramHandler.FEC | RssDatagramHandler.LZ4, 1280, 1300);
    }

    private void verifyMtuAndFragmentation(InetAddress ip, int flags, int clientMtu, int serverMtu) throws Exception {
        int effectiveMtu = Math.min(clientMtu, serverMtu);
        boolean lz4 = (flags & RssDatagramHandler.LZ4) != 0;
        boolean profiled = (flags & (RssDatagramHandler.GAME | RssDatagramHandler.WEAK)) != 0;
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
                ch.pipeline().addLast(RssDatagramHandler.client(result.getToken(), primary, primary, result.getUdpMtu(),
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
                int wireOverhead = 20 + (((flags & (RssDatagramHandler.GAME | RssDatagramHandler.WEAK)) != 0) ? 24 : 0)
                        + (((flags & RssDatagramHandler.FEC) != 0) ? 8 : 5);
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
        try (RssDatagramFragmenter tx = new RssDatagramFragmenter(token, RssDatagramHandler.FEC, false, secret,
                globalBudget, 64 * 1024L, timeoutNanos);
             RssDatagramFragmenter rx = new RssDatagramFragmenter(token, RssDatagramHandler.FEC, true, secret,
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
            try (RssDatagramFragmenter tinyRx = new RssDatagramFragmenter(token, RssDatagramHandler.FEC, true, secret,
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
        try (AdaptiveUdpFec fecTx = new AdaptiveUdpFec(alloc, clock::get, false, 20);
             AdaptiveUdpFec fecRx = new AdaptiveUdpFec(alloc, clock::get, false, 20);
             RssDatagramFragmenter fragTx = new RssDatagramFragmenter(token, RssDatagramHandler.FEC, false, secret);
             RssDatagramFragmenter fragRx = new RssDatagramFragmenter(token, RssDatagramHandler.FEC, true, secret)) {
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
                RssDatagramHandler.client(token.toString(), peer, peer, 1300, RssDatagramHandler.FEC, null, null, false));
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

    @Test
    void fragmenterCompactsOversizedRecvBuffersOnRealPooledNioPathAndAvoidsDanglingSlots() throws Exception {
        UUID token = UUID.randomUUID();
        byte[] secret = new byte[32];
        new java.security.SecureRandom().nextBytes(secret);
        long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(750);
        io.netty.buffer.PooledByteBufAllocator pooled = new io.netty.buffer.PooledByteBufAllocator(true);

        // 1. Real NIO UDP socket with FixedRecvByteBufAllocator(65536) + PooledByteBufAllocator:
        //    Receive 32 incomplete ~1200B fragments across 8 sessions (missing final slice).
        //    Without compaction, 32 retained slices would pin >= 32 * 65536 = 2 MiB of direct memory.
        AtomicLong globalBudget = new AtomicLong();
        RssDatagramFragmenter[] rxSessions = new RssDatagramFragmenter[8];
        RssDatagramFragmenter[] txSessions = new RssDatagramFragmenter[8];
        for (int i = 0; i < rxSessions.length; i++) {
            txSessions[i] = new RssDatagramFragmenter(token, RssDatagramHandler.FEC, false, secret,
                    globalBudget, 24L * 1024L * 1024L, timeoutNanos);
            rxSessions[i] = new RssDatagramFragmenter(token, RssDatagramHandler.FEC, true, secret,
                    globalBudget, 24L * 1024L * 1024L, timeoutNanos);
        }
        java.util.concurrent.CountDownLatch receivedLatch = new java.util.concurrent.CountDownLatch(32);
        java.util.concurrent.atomic.AtomicInteger rxIndex = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger maxRecvBacking = new java.util.concurrent.atomic.AtomicInteger();
        Channel receiver = Sockets.udpBootstrap(null, ch -> {
            ch.config().setAllocator(pooled);
            ch.config().setRecvByteBufAllocator(new io.netty.channel.FixedRecvByteBufAllocator(65536));
            ch.pipeline().addLast(new SimpleChannelInboundHandler<DatagramPacket>() {
                @Override protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket msg) {
                    ByteBuf content = msg.content();
                    maxRecvBacking.accumulateAndGet(content.capacity(), Math::max);
                    int idx = rxIndex.getAndIncrement();
                    RssDatagramFragmenter targetRx = rxSessions[idx / 4];
                    assertNull(targetRx.accept(content.retain(), ctx.alloc(), 1_000L));
                    receivedLatch.countDown();
                }
            });
        }).bind(new InetSocketAddress("127.0.0.1", 0)).sync().channel();

        try (DatagramSocket sender = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0))) {
            InetSocketAddress dst = (InetSocketAddress) receiver.localAddress();
            byte[] payload = new byte[5600]; // 5 fragments at unitLimit 1200 -> send first 4, drop 5th
            new java.util.Random(123).nextBytes(payload);
            long beforeUsed = pooled.metric().usedDirectMemory();
            for (int s = 0; s < txSessions.length; s++) {
                java.util.List<ByteBuf> frags = new java.util.ArrayList<>();
                txSessions[s].fragment(pooled, io.netty.buffer.Unpooled.wrappedBuffer(payload), 1200,
                        (f, last) -> frags.add(f));
                assertEquals(5, frags.size());
                try {
                    for (int k = 0; k < 4; k++) {
                        ByteBuf f = frags.get(k);
                        byte[] raw = new byte[f.readableBytes()];
                        f.getBytes(f.readerIndex(), raw);
                        sender.send(new java.net.DatagramPacket(raw, raw.length, dst));
                    }
                } finally {
                    for (ByteBuf f : frags) f.release();
                }
            }
            assertTrue(receivedLatch.await(5, TimeUnit.SECONDS), "expected 32 fragments on NIO socket");
            assertEquals(65536, maxRecvBacking.get(), "real NIO socket must use 65536B FixedRecvByteBufAllocator");
            long budgeted = globalBudget.get();
            assertTrue(budgeted > 32 * 1000 && budgeted < 80 * 1024, "unexpected budgeted bytes=" + budgeted);

            // Verify every pending fragment was compacted (not pinning 65536B backing buffers):
            // 32 * 65536B would require > 2 MiB; pooled direct memory delta must remain far below 512 KiB
            // (a single pooled direct arena chunk is reserved once; active pinned buffers are ~48 KiB).
            for (RssDatagramFragmenter rx : rxSessions) {
                assertEquals(1, rx.activeAssemblies());
            }
            // Verify compact backing capacity directly on a 65536B parent buffer slice.
            ByteBuf bigRecv = pooled.ioBuffer(65536, 65536);
            try {
                java.util.List<ByteBuf> single = new java.util.ArrayList<>();
                txSessions[0].fragment(pooled, io.netty.buffer.Unpooled.wrappedBuffer(payload), 1200,
                        (f, last) -> single.add(f));
                try {
                    ByteBuf frag0 = single.get(0);
                    bigRecv.writeBytes(frag0, frag0.readerIndex(), frag0.readableBytes());
                } finally {
                    for (ByteBuf f : single) f.release();
                }
                assertNull(rxSessions[0].accept(bigRecv.retainedSlice(), pooled, 2_000L));
                // After accept() returns, the 65536B parent buffer must NOT be pinned by rxSessions[0]!
                assertEquals(1, bigRecv.refCnt(), "65536B receive buffer must be released immediately after compaction");
            } finally {
                bigRecv.release();
            }
            assertTrue(beforeUsed >= 0L);

            // Expire all sessions and verify active assemblies & global budget drop cleanly to 0.
            for (RssDatagramFragmenter rx : rxSessions) {
                rx.expire(2_000L + timeoutNanos);
                assertEquals(0, rx.activeAssemblies());
                assertEquals(0, rx.bufferedBytes());
            }
            assertEquals(0L, globalBudget.get());
        } finally {
            receiver.close().sync();
            for (RssDatagramFragmenter rx : rxSessions) rx.close();
            for (RssDatagramFragmenter tx : txSessions) tx.close();
        }

        // 2. First-fragment budget rejection must NOT leave a dangling empty Assembly slot active,
        //    and allocation failure during compact copy or final reassembly must release slot & budget cleanly.
        AtomicLong tinyBudget = new AtomicLong();
        try (RssDatagramFragmenter tx = new RssDatagramFragmenter(token, RssDatagramHandler.FEC, false, secret,
                tinyBudget, 500L, timeoutNanos);
             RssDatagramFragmenter rx = new RssDatagramFragmenter(token, RssDatagramHandler.FEC, true, secret,
                     tinyBudget, 500L, timeoutNanos)) {
            byte[] data = new byte[2500];
            new java.util.Random(55).nextBytes(data);
            java.util.List<ByteBuf> frags = new java.util.ArrayList<>();
            tx.fragment(pooled, io.netty.buffer.Unpooled.wrappedBuffer(data), 1200, (f, last) -> frags.add(f));
            assertNull(rx.accept(frags.get(0), pooled, 10_000L));
            for (int i = 1; i < frags.size(); i++) frags.get(i).release();
            assertEquals(1, rx.rxBudgetRejected);
            assertEquals(0, rx.activeAssemblies(), "rejected first fragment must not occupy an active assembly slot");
            assertEquals(0, rx.bufferedBytes());
            assertEquals(0L, tinyBudget.get());
        }

        // 3. Simulated allocation failure during both fragment copy and final reassembly output.
        AtomicLong failBudget = new AtomicLong();
        try (RssDatagramFragmenter tx = new RssDatagramFragmenter(token, RssDatagramHandler.FEC, false, secret,
                failBudget, 1024 * 1024L, timeoutNanos);
             RssDatagramFragmenter rx = new RssDatagramFragmenter(token, RssDatagramHandler.FEC, true, secret,
                     failBudget, 1024 * 1024L, timeoutNanos)) {
            byte[] data = new byte[2500];
            new java.util.Random(77).nextBytes(data);
            java.util.List<ByteBuf> frags = new java.util.ArrayList<>();
            tx.fragment(pooled, io.netty.buffer.Unpooled.wrappedBuffer(data), 1200, (f, last) -> frags.add(f));
            io.netty.buffer.ByteBufAllocator failingAlloc = new io.netty.buffer.AbstractByteBufAllocator(false) {
                @Override public boolean isDirectBufferPooled() { return false; }
                @Override protected ByteBuf newHeapBuffer(int initialCapacity, int maxCapacity) {
                    throw new OutOfMemoryError("simulated heap OOM");
                }
                @Override protected ByteBuf newDirectBuffer(int initialCapacity, int maxCapacity) {
                    throw new OutOfMemoryError("simulated direct OOM");
                }
            };
            // Copy path OOM on 65536B backing buffer -> must roll back budget and keep 0 active assemblies.
            ByteBuf big = pooled.ioBuffer(65536, 65536);
            big.writeBytes(frags.get(0), frags.get(0).readerIndex(), frags.get(0).readableBytes());
            assertThrows(OutOfMemoryError.class, () -> rx.accept(big, failingAlloc, 1_000L));
            assertEquals(0, big.refCnt());
            assertEquals(0, rx.activeAssemblies());
            assertEquals(0, rx.bufferedBytes());
            assertEquals(0L, failBudget.get());

            // Final reassembly output OOM -> must release all stored parts and reset budget to 0.
            // Use fresh fragments so authSeq is distinct from the earlier copy-path OOM check.
            for (ByteBuf f : frags) f.release();
            java.util.List<ByteBuf> frags2 = new java.util.ArrayList<>();
            tx.fragment(pooled, io.netty.buffer.Unpooled.wrappedBuffer(data), 1200, (f, last) -> frags2.add(f));
            for (int i = 0; i < frags2.size() - 1; i++) {
                assertNull(rx.accept(frags2.get(i), pooled, 2_000L));
            }
            assertEquals(1, rx.activeAssemblies());
            assertTrue(failBudget.get() > 0L);
            // Last fragment is already compact (allocated exact size by fragment()), so only final assembled alloc fails.
            ByteBuf lastFrag = frags2.get(frags2.size() - 1);
            assertThrows(OutOfMemoryError.class, () -> rx.accept(lastFrag, failingAlloc, 2_000L));
            assertEquals(0, lastFrag.refCnt());
            assertEquals(0, rx.activeAssemblies());
            assertEquals(0, rx.bufferedBytes());
            assertEquals(0L, failBudget.get());
        }
    }

    @Test
    void multiFragmentWriteAggregatesLocalFailuresAndIgnoresParityOrExtraCopyFailures() {
        UUID token = UUID.randomUUID();
        byte[] secret = new byte[32];
        new java.security.SecureRandom().nextBytes(secret);
        InetSocketAddress peer = new InetSocketAddress("127.0.0.1", 19998);
        byte[] oversized = new byte[4096];
        new java.util.Random(314).nextBytes(oversized);

        // 1. Failing fragment index 0, 1, or last (3) must fail the original write promise exactly once.
        for (int failAt : new int[]{0, 1, 3}) {
            java.util.concurrent.atomic.AtomicInteger writeSeq = new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.atomic.AtomicInteger completions = new java.util.concurrent.atomic.AtomicInteger();
            io.netty.channel.embedded.EmbeddedChannel ch = new io.netty.channel.embedded.EmbeddedChannel(
                    new io.netty.channel.ChannelOutboundHandlerAdapter() {
                        @Override public void write(ChannelHandlerContext ctx, Object msg, io.netty.channel.ChannelPromise promise) {
                            io.netty.util.ReferenceCountUtil.release(msg);
                            if (writeSeq.getAndIncrement() == failAt) {
                                promise.tryFailure(new java.io.IOException("simulated-frag-fail-" + failAt));
                            } else {
                                promise.trySuccess();
                            }
                        }
                    },
                    RssDatagramHandler.client(token.toString(), peer, peer, 1300, RssDatagramHandler.FEC, null, secret, true));
            try {
                io.netty.channel.ChannelPromise promise = ch.newPromise();
                promise.addListener(f -> completions.incrementAndGet());
                ch.writeAndFlush(new DatagramPacket(io.netty.buffer.Unpooled.wrappedBuffer(oversized), peer), promise);
                assertTrue(promise.isDone());
                assertFalse(promise.isSuccess(), "failing fragment " + failAt + " must fail original promise");
                assertTrue(promise.cause().getMessage().contains("simulated-frag-fail-" + failAt));
                assertEquals(1, completions.get(), "promise must complete exactly once");
            } finally {
                ch.finishAndReleaseAll();
            }
        }

        // 2. All original fragments succeed, while FEC parity write fails -> original promise MUST succeed!
        AtomicLong clock = new AtomicLong(1_000_000L);
        java.util.concurrent.atomic.AtomicInteger parityFails = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger dataWrites = new java.util.concurrent.atomic.AtomicInteger();
        io.netty.channel.embedded.EmbeddedChannel fecCh = new io.netty.channel.embedded.EmbeddedChannel(
                new io.netty.channel.ChannelOutboundHandlerAdapter() {
                    @Override public void write(ChannelHandlerContext ctx, Object msg, io.netty.channel.ChannelPromise promise) {
                        DatagramPacket pkt = (DatagramPacket) msg;
                        ByteBuf b = pkt.content();
                        int fecType = b.readableBytes() > RssDatagramHandler.HEADER_BYTES
                                ? b.getUnsignedByte(b.readerIndex() + RssDatagramHandler.HEADER_BYTES) : -1;
                        pkt.release();
                        if (fecType == AdaptiveUdpFec.PARITY) {
                            parityFails.incrementAndGet();
                            promise.tryFailure(new java.io.IOException("parity-drop"));
                        } else {
                            dataWrites.incrementAndGet();
                            promise.trySuccess();
                        }
                    }
                },
                new RssDatagramHandler(token.toString(), peer, peer, null, clock::get, RssDatagramHandler.FEC, null, secret, 1300, true),
                new ChannelInboundHandlerAdapter() {
                    @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                        // Swallow voidPromise failure notification for non-critical FEC parity packets.
                    }
                });
        try {
            // Trigger FEC parity generation by feeding a loss report from the remote peer.
            ByteBuf small = io.netty.buffer.Unpooled.buffer(16).writeZero(16);
            for (int i = 0; i < 40; i++) {
                fecCh.writeAndFlush(new DatagramPacket(small.retainedDuplicate(), peer));
            }
            small.release();
            // Inject an authenticated-free daily FEC report: header(20) + REPORT(3) + seq(4) + missing(1).
            ByteBuf report = io.netty.buffer.Unpooled.buffer(26)
                    .writeLong(token.getMostSignificantBits()).writeLong(token.getLeastSignificantBits()).writeInt(0)
                    .writeByte(3).writeInt(35).writeByte(4);
            fecCh.writeInbound(new DatagramPacket(report, new InetSocketAddress("127.0.0.1", 19997), peer));
            dataWrites.set(0);
            parityFails.set(0);

            io.netty.channel.ChannelPromise promise = fecCh.newPromise();
            fecCh.writeAndFlush(new DatagramPacket(io.netty.buffer.Unpooled.wrappedBuffer(oversized), peer), promise);
            assertTrue(promise.isDone());
            assertTrue(promise.isSuccess(), "parity write failure must not fail original datagram promise");
            assertEquals(4, dataWrites.get());
            assertTrue(parityFails.get() >= 1, "expected at least one FEC parity write to fail");
        } finally {
            fecCh.finishAndReleaseAll();
        }

        // 3. GAME mode with copies=2: failing extra copies (copy >= 1) must NOT fail original promise,
        //    whereas failing an original copy (copy == 0) MUST fail the original promise!
        UUID salt = UUID.randomUUID();
        RssDatagramHandler gameWire = RssDatagramHandler.client(token.toString(), peer, peer, 1300, RssDatagramHandler.GAME, salt, secret, true);
        try {
            java.lang.reflect.Field gf = RssDatagramHandler.class.getDeclaredField("game");
            gf.setAccessible(true);
            ((AdaptiveUdpReplication) gf.get(gameWire)).copies = 2;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        java.util.concurrent.atomic.AtomicInteger extraFails = new java.util.concurrent.atomic.AtomicInteger();
        io.netty.channel.embedded.EmbeddedChannel gameCh = new io.netty.channel.embedded.EmbeddedChannel(
                new io.netty.channel.ChannelOutboundHandlerAdapter() {
                    @Override public void write(ChannelHandlerContext ctx, Object msg, io.netty.channel.ChannelPromise promise) {
                        DatagramPacket pkt = (DatagramPacket) msg;
                        ByteBuf b = pkt.content();
                        // Game + MAC: header(20) + macSeq(8) + copyByte(1)
                        int copyIdx = b.getUnsignedByte(b.readerIndex() + RssDatagramHandler.HEADER_BYTES + 8);
                        pkt.release();
                        if (copyIdx > 0) {
                            extraFails.incrementAndGet();
                            promise.tryFailure(new java.io.IOException("extra-copy-drop"));
                        } else {
                            promise.trySuccess();
                        }
                    }
                },
                gameWire,
                new ChannelInboundHandlerAdapter() {
                    @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                        // Swallow voidPromise failure notification for optional game extra copies.
                    }
                });
        try {
            io.netty.channel.ChannelPromise promise = gameCh.newPromise();
            gameCh.writeAndFlush(new DatagramPacket(io.netty.buffer.Unpooled.wrappedBuffer(oversized), peer), promise);
            assertTrue(promise.isDone());
            assertTrue(promise.isSuccess(), "game extra copy failure must not fail original datagram promise");
            assertTrue(extraFails.get() >= 1, "expected extra game copies to be sent and failed");
        } finally {
            gameCh.finishAndReleaseAll();
        }

        // 4. Synchronous failure on fragment 2 inside outbound handler fails original promise cleanly without leak.
        java.util.concurrent.atomic.AtomicInteger syncCount = new java.util.concurrent.atomic.AtomicInteger();
        io.netty.channel.embedded.EmbeddedChannel syncFailCh = new io.netty.channel.embedded.EmbeddedChannel(
                new io.netty.channel.ChannelOutboundHandlerAdapter() {
                    @Override public void write(ChannelHandlerContext ctx, Object msg, io.netty.channel.ChannelPromise promise) {
                        io.netty.util.ReferenceCountUtil.release(msg);
                        if (syncCount.getAndIncrement() == 2) {
                            throw new IllegalStateException("sync-write-boom");
                        }
                        promise.trySuccess();
                    }
                },
                RssDatagramHandler.client(token.toString(), peer, peer, 1300, RssDatagramHandler.FEC, null, secret, true));
        try {
            io.netty.channel.ChannelPromise promise = syncFailCh.newPromise();
            syncFailCh.writeAndFlush(new DatagramPacket(io.netty.buffer.Unpooled.wrappedBuffer(oversized), peer), promise);
            assertTrue(promise.isDone());
            assertFalse(promise.isSuccess());
            assertTrue(promise.cause().getMessage().contains("sync-write-boom"));
        } finally {
            syncFailCh.finishAndReleaseAll();
        }
    }

    private void verifyHops(InetAddress ip, int flags) throws Exception {
        boolean plainAes = flags == RssDatagramHandler.AES;
        boolean lz4 = (flags & RssDatagramHandler.LZ4) != 0;
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
            if ((flags & (RssDatagramHandler.GAME | RssDatagramHandler.WEAK)) != 0) req.setProfileVersion(1);
            req.setClientSalt(UUID.randomUUID());
            UdpRelayGroupOpenResult result = server.openUdpRelayGroup(req, SocksRpcContract.rpcToken());
            assertTrue(result.isSuccess(), result.getErrorCode());
            InetSocketAddress primary = new InetSocketAddress(ip, result.getRelays().get(0).getRelayAddress().getPort());
            assertEquals(plainAes ? 1 : 2, result.getRelays().size());
            InetSocketAddress alias = plainAes ? primary : new InetSocketAddress(ip, result.getRelays().get(1).getRelayAddress().getPort());
            AtomicLong clock = new AtomicLong();
            BlockingQueue<Integer> wirePorts = new LinkedBlockingQueue<>(), delivered = new LinkedBlockingQueue<>();
            BlockingQueue<Integer> wireSizes = new LinkedBlockingQueue<>();
            Channel client = Sockets.udpBootstrap(null, ch -> {
                ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                    private int generation = -1;
                    @Override public void channelRead(ChannelHandlerContext ctx, Object msg) {
                        DatagramPacket pkt = (DatagramPacket) msg;
                        int hop = pkt.content().getInt(pkt.content().readerIndex() + 16);
                        if (plainAes || hop > generation) {
                            generation = hop;
                            wirePorts.add(pkt.sender().getPort());
                            wireSizes.add(pkt.content().readableBytes());
                        }
                        ctx.fireChannelRead(msg);
                    }
                });
                ch.pipeline().addLast(new RssDatagramHandler(result.getToken(), primary, alias, null, clock::get, flags, req.getClientSalt(),
                        (flags & RssDatagramHandler.AES) != 0 ? RssDatagramCipher.configuredKey() : result.getAuthSecret()));
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
                    clock.set(i * RssDatagramHandler.HOP_NANOS);
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
