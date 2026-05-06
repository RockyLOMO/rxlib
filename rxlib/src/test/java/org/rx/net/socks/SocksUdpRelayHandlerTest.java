package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.socket.DatagramPacket;
import org.junit.jupiter.api.Test;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.socks.upstream.Upstream;
import org.rx.net.socks.upstream.SocksUdpUpstream;
import org.rx.net.socks.upstream.UdpClientUpstream;
import org.rx.net.support.UpstreamSupport;
import org.rx.net.support.UnresolvedEndpoint;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SocksUdpRelayHandlerTest {
    static final class AsyncUpstream extends Upstream {
        final CompletableFuture<Void> readyFuture = new CompletableFuture<>();

        AsyncUpstream(UnresolvedEndpoint dstEp) {
            super(dstEp);
        }

        @Override
        public CompletableFuture<Void> initChannelAsync(Channel channel) {
            return readyFuture;
        }
    }

    static final class FakePortHoppingUpstream extends SocksUdpUpstream {
        final InetSocketAddress[] relayAddresses;
        final AtomicInteger nextIndex = new AtomicInteger();

        FakePortHoppingUpstream(UnresolvedEndpoint dstEp, SocksConfig config, InetSocketAddress... relayAddresses) {
            super(dstEp, config, new UpstreamSupport(new AuthenticEndpoint(relayAddresses[0]), null));
            this.relayAddresses = relayAddresses;
        }

        @Override
        public CompletableFuture<Void> initChannelAsync(Channel channel) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public InetSocketAddress getUdpRelayAddress(Channel channel) {
            return relayAddresses[0];
        }

        @Override
        public InetSocketAddress selectUdpRelayAddress(Channel channel) {
            int index = nextIndex.getAndIncrement();
            return relayAddresses[index % relayAddresses.length];
        }

        @Override
        public InetSocketAddress selectUdpRelayAddressAndRecord(Channel channel, int bytes) {
            return selectUdpRelayAddress(channel);
        }

        @Override
        public InetSocketAddress[] snapshotUdpRelayAddresses(Channel channel) {
            return relayAddresses;
        }

        @Override
        public boolean ownsUdpRelayAddress(Channel channel, InetSocketAddress sender) {
            for (InetSocketAddress relayAddress : relayAddresses) {
                if (relayAddress.equals(sender)) {
                    return true;
                }
            }
            return false;
        }
    }

    @Test
    void queuesClientPacketUntilAsyncRouteReady() throws Exception {
        SocksConfig config = new SocksConfig(new LocalAddress("UDP_ROUTE_PENDING_TEST"));
        config.getWhiteList();
        SocksProxyServer server = new SocksProxyServer(config);
        EmbeddedChannel relay = new EmbeddedChannel(SocksUdpRelayHandler.DEFAULT);
        try {
            AsyncUpstream upstream = new AsyncUpstream(new UnresolvedEndpoint("127.0.0.1", 15300));
            server.onUdpRoute.replace((s, e) -> e.setUpstream(upstream));
            relay.attr(SocksContext.SOCKS_SVR).set(server);

            InetSocketAddress clientAddr = new InetSocketAddress("127.0.0.1", 22001);
            ByteBuf packetBuf = Unpooled.buffer();
            packetBuf.writeZero(3);
            packetBuf.writeByte(0x01);
            packetBuf.writeBytes(new byte[]{127, 0, 0, 1});
            packetBuf.writeShort(15300);
            packetBuf.writeBytes("queued-udp".getBytes(StandardCharsets.UTF_8));

            DatagramPacket inbound = new DatagramPacket(packetBuf,
                    new InetSocketAddress("127.0.0.1", 1080), clientAddr);

            assertDoesNotThrow(() -> relay.writeInbound(inbound));
            assertNull(relay.readOutbound(), "route pending时不应立即写出");

            upstream.readyFuture.complete(null);
            DatagramPacket outbound = null;
            long deadline = System.currentTimeMillis() + 3000L;
            while (System.currentTimeMillis() < deadline && outbound == null) {
                relay.runPendingTasks();
                relay.runScheduledPendingTasks();
                outbound = relay.readOutbound();
                if (outbound == null) {
                    Thread.sleep(20L);
                }
            }
            assertNotNull(outbound, "route ready后应回放首包");
            try {
                assertEquals(new InetSocketAddress("127.0.0.1", 15300), outbound.recipient());
                byte[] payload = new byte[outbound.content().readableBytes()];
                outbound.content().readBytes(payload);
                assertEquals("queued-udp", new String(payload, StandardCharsets.UTF_8));
            } finally {
                outbound.release();
            }
        } finally {
            relay.finishAndReleaseAll();
            server.close();
        }
    }

    @Test
    void resolvesDomainBeforeDirectUdpWrite() throws Exception {
        SocksConfig config = new SocksConfig(new LocalAddress("UDP_DOMAIN_DIRECT_TEST"));
        config.getWhiteList();
        SocksProxyServer server = new SocksProxyServer(config);
        EmbeddedChannel relay = new EmbeddedChannel(SocksUdpRelayHandler.DEFAULT);
        try {
            relay.attr(SocksContext.SOCKS_SVR).set(server);

            InetSocketAddress clientAddr = new InetSocketAddress("127.0.0.1", 22002);
            ByteBuf packetBuf = Unpooled.buffer();
            packetBuf.writeZero(3);
            UdpManager.encode(packetBuf, "localhost", 15301);
            packetBuf.writeBytes("domain-udp".getBytes(StandardCharsets.UTF_8));

            DatagramPacket inbound = new DatagramPacket(packetBuf,
                    new InetSocketAddress("127.0.0.1", 1080), clientAddr);

            assertDoesNotThrow(() -> relay.writeInbound(inbound));

            DatagramPacket outbound = null;
            long deadline = System.currentTimeMillis() + 3000L;
            while (System.currentTimeMillis() < deadline && outbound == null) {
                relay.runPendingTasks();
                relay.runScheduledPendingTasks();
                outbound = relay.readOutbound();
                if (outbound == null) {
                    Thread.sleep(20L);
                }
            }
            assertNotNull(outbound, "域名目的地解析完成后应写出首包");
            try {
                assertFalse(outbound.recipient().isUnresolved(), "UDP recipient 不能保持 unresolved");
                assertEquals(15301, outbound.recipient().getPort());
                byte[] payload = new byte[outbound.content().readableBytes()];
                outbound.content().readBytes(payload);
                assertEquals("domain-udp", new String(payload, StandardCharsets.UTF_8));
            } finally {
                outbound.release();
            }
        } finally {
            relay.finishAndReleaseAll();
            server.close();
        }
    }

    @Test
    void socksUdpUpstreamRegistersAndRotatesPortHoppingRelays() throws Exception {
        SocksConfig config = new SocksConfig(new LocalAddress("UDP_PORT_HOPPING_TEST"));
        config.getWhiteList();
        SocksProxyServer server = new SocksProxyServer(config);
        EmbeddedChannel relay = new EmbeddedChannel(SocksUdpRelayHandler.DEFAULT);
        try {
            InetSocketAddress firstRelay = new InetSocketAddress("127.0.0.1", 23001);
            InetSocketAddress secondRelay = new InetSocketAddress("127.0.0.1", 23002);
            FakePortHoppingUpstream upstream = new FakePortHoppingUpstream(
                    new UnresolvedEndpoint("127.0.0.1", 15302), config, firstRelay, secondRelay);
            server.onUdpRoute.replace((s, e) -> e.setUpstream(upstream));
            relay.attr(SocksContext.SOCKS_SVR).set(server);

            InetSocketAddress clientAddr = new InetSocketAddress("127.0.0.1", 22003);
            assertDoesNotThrow(() -> relay.writeInbound(socks5Packet(clientAddr, "hop-1")));
            DatagramPacket first = readOutbound(relay);
            assertNotNull(first);
            try {
                assertEquals(firstRelay, first.recipient());
            } finally {
                first.release();
            }

            ConcurrentMap<InetSocketAddress, SocksContext> ctxMap = relay.attr(SocksUdpRelayHandler.ATTR_CTX_MAP).get();
            assertNotNull(ctxMap);
            assertTrue(ctxMap.containsKey(firstRelay));
            assertTrue(ctxMap.containsKey(secondRelay));

            assertDoesNotThrow(() -> relay.writeInbound(socks5Packet(clientAddr, "hop-2")));
            DatagramPacket second = readOutbound(relay);
            assertNotNull(second);
            try {
                assertEquals(secondRelay, second.recipient());
            } finally {
                second.release();
            }
        } finally {
            relay.finishAndReleaseAll();
            server.close();
        }
    }

    @Test
    void udpClientUpstreamPreservesSocks5HeaderBothDirections() throws Exception {
        SocksConfig config = new SocksConfig(new LocalAddress("UDP_CLIENT_UPSTREAM_TEST"));
        config.getWhiteList();
        SocksProxyServer server = new SocksProxyServer(config);
        EmbeddedChannel relay = new EmbeddedChannel(SocksUdpRelayHandler.DEFAULT);
        try {
            InetSocketAddress udpClient = new InetSocketAddress("127.0.0.1", 23201);
            server.onUdpRoute.replace((s, e) -> e.setUpstream(
                    new UdpClientUpstream(e.getFirstDestination(), config, udpClient)));
            relay.attr(SocksContext.SOCKS_SVR).set(server);

            InetSocketAddress clientAddr = new InetSocketAddress("127.0.0.1", 22201);
            assertDoesNotThrow(() -> relay.writeInbound(socks5Packet(clientAddr, "udp-client-req")));
            DatagramPacket request = readOutbound(relay);
            assertNotNull(request);
            try {
                assertEquals(udpClient, request.recipient());
                assertTrue(UdpManager.isValidSocks5UdpPacket(request.content()));
                UnresolvedEndpoint dstEp = UdpManager.socks5Decode(request.content());
                assertEquals(15302, dstEp.getPort());
                byte[] payload = new byte[request.content().readableBytes()];
                request.content().readBytes(payload);
                assertEquals("udp-client-req", new String(payload, StandardCharsets.UTF_8));
            } finally {
                request.release();
            }

            ByteBuf responseBuf = Unpooled.buffer();
            responseBuf.writeZero(3);
            UdpManager.encode(responseBuf, "127.0.0.1", 15302);
            responseBuf.writeBytes("udp-client-res".getBytes(StandardCharsets.UTF_8));
            assertDoesNotThrow(() -> relay.writeInbound(
                    new DatagramPacket(responseBuf, new InetSocketAddress("127.0.0.1", 1080), udpClient)));
            DatagramPacket response = readOutbound(relay);
            assertNotNull(response);
            try {
                assertEquals(clientAddr, response.recipient());
                assertTrue(UdpManager.isValidSocks5UdpPacket(response.content()));
                UnresolvedEndpoint srcEp = UdpManager.socks5Decode(response.content());
                assertEquals(15302, srcEp.getPort());
                byte[] payload = new byte[response.content().readableBytes()];
                response.content().readBytes(payload);
                assertEquals("udp-client-res", new String(payload, StandardCharsets.UTF_8));
            } finally {
                response.release();
            }
        } finally {
            relay.finishAndReleaseAll();
            server.close();
        }
    }

    @Test
    void redundantClientPeerAddedOnlyWhenClientSenderChanges() throws Exception {
        SocksConfig config = new SocksConfig(new LocalAddress("UDP_REDUNDANT_CLIENT_PEER_CHANGE_TEST"));
        config.getWhiteList();
        SocksProxyServer server = new SocksProxyServer(config);
        EmbeddedChannel relay = new EmbeddedChannel(SocksUdpRelayHandler.DEFAULT);
        try {
            relay.attr(SocksContext.SOCKS_SVR).set(server);
            relay.attr(UdpRelayAttributes.ATTR_REDUNDANT_CLIENT_PEER).set(Boolean.TRUE);
            UdpRelayAttributes.initRedundantPeers(relay);

            InetSocketAddress senderA = new InetSocketAddress("127.0.0.1", 22101);
            assertDoesNotThrow(() -> relay.writeInbound(socks5Packet(senderA, "rdnt-a1")));
            DatagramPacket first = readOutbound(relay);
            assertNotNull(first);
            first.release();

            ConcurrentMap<InetSocketAddress, Boolean> peers = relay.attr(UdpRelayAttributes.ATTR_REDUNDANT_PEERS).get();
            assertNotNull(peers);
            assertEquals(1, peers.size());
            assertTrue(peers.containsKey(UdpRelayAttributes.normalize(senderA)));

            assertDoesNotThrow(() -> relay.writeInbound(socks5Packet(senderA, "rdnt-a2")));
            DatagramPacket second = readOutbound(relay);
            assertNotNull(second);
            second.release();
            assertEquals(1, peers.size());

            InetSocketAddress senderB = new InetSocketAddress("127.0.0.1", 22102);
            assertDoesNotThrow(() -> relay.writeInbound(socks5Packet(senderB, "rdnt-b1")));
            DatagramPacket third = readOutbound(relay);
            assertNotNull(third);
            third.release();

            assertEquals(2, peers.size());
            assertTrue(peers.containsKey(UdpRelayAttributes.normalize(senderB)));
            assertEquals(senderB, relay.attr(SocksUdpRelayHandler.ATTR_CLIENT_ADDR).get());
        } finally {
            relay.finishAndReleaseAll();
            server.close();
        }
    }

    @Test
    void upstreamRelayAddAndRemoveMaintainsCtxMapPrecisely() {
        EmbeddedChannel relay = new EmbeddedChannel(SocksUdpRelayHandler.DEFAULT);
        try {
            InetSocketAddress firstRelay = new InetSocketAddress("127.0.0.1", 23101);
            InetSocketAddress secondRelay = new InetSocketAddress("127.0.0.1", 23102);
            InetSocketAddress staleRelay = new InetSocketAddress("127.0.0.1", 23103);
            SocksConfig config = new SocksConfig(new LocalAddress("UDP_PORT_HOPPING_CLEANUP_TEST"));
            FakePortHoppingUpstream upstream = new FakePortHoppingUpstream(
                    new UnresolvedEndpoint("127.0.0.1", 15303), config, firstRelay, secondRelay);
            SocksContext context = SocksContext.getCtx(new InetSocketAddress("127.0.0.1", 22103),
                    new UnresolvedEndpoint("127.0.0.1", 15303));
            context.setUpstream(upstream);

            ConcurrentMap<InetSocketAddress, SocksContext> ctxMap = new ConcurrentHashMap<>();
            ctxMap.put(firstRelay, context);
            ctxMap.put(secondRelay, context);
            ctxMap.put(staleRelay, context);
            relay.attr(SocksUdpRelayHandler.ATTR_CTX_MAP).set(ctxMap);
            ConcurrentMap<InetSocketAddress, SocksContext> udp2rawCtxMap = new ConcurrentHashMap<>();
            udp2rawCtxMap.put(firstRelay, context);
            udp2rawCtxMap.put(secondRelay, context);
            udp2rawCtxMap.put(staleRelay, context);
            relay.attr(Udp2rawHandler.ATTR_CTX_MAP).set(udp2rawCtxMap);

            ConcurrentMap<UnresolvedEndpoint, SocksContext> routeMap = new ConcurrentHashMap<>();
            UnresolvedEndpoint routeKey = new UnresolvedEndpoint("127.0.0.1", 15303);
            routeMap.put(routeKey, context);
            relay.attr(SocksUdpRelayHandler.ATTR_ROUTE_MAP).set(routeMap);
            ConcurrentMap<UnresolvedEndpoint, SocksContext> udp2rawRouteMap = new ConcurrentHashMap<>();
            udp2rawRouteMap.put(routeKey, context);
            relay.attr(Udp2rawHandler.ATTR_ROUTE_MAP).set(udp2rawRouteMap);
            relay.attr(SocksUdpRelayHandler.ATTR_LAST_ROUTE).set(
                    new SocksUdpRelayHandler.LastRoute(UdpManager.socks5HeaderTemplate(routeKey), context));

            InetSocketAddress replenishedRelay = new InetSocketAddress("127.0.0.1", 23104);
            SocksUdpRelayHandler.onUpstreamRelayAdded(relay, replenishedRelay, upstream);
            relay.runPendingTasks();
            assertSame(context, ctxMap.get(replenishedRelay));
            assertSame(context, udp2rawCtxMap.get(replenishedRelay));

            SocksUdpRelayHandler.onUpstreamRelayRemoved(relay, secondRelay, upstream);
            relay.runPendingTasks();
            assertFalse(ctxMap.containsKey(secondRelay));
            assertFalse(udp2rawCtxMap.containsKey(secondRelay));
            assertSame(context, routeMap.get(routeKey), "单 hop 摘除不能清理 routeMap");
            assertSame(context, udp2rawRouteMap.get(routeKey), "单 hop 摘除不能清理 udp2raw routeMap");

            SocksUdpRelayHandler.onUpstreamSessionInvalidated(relay,
                    new InetSocketAddress[]{firstRelay, replenishedRelay}, upstream);
            relay.runPendingTasks();
            assertFalse(ctxMap.containsKey(firstRelay));
            assertFalse(ctxMap.containsKey(replenishedRelay));
            assertSame(context, ctxMap.get(staleRelay), "session cleanup 只清理传入快照内的 relayAddr");
            assertFalse(routeMap.containsKey(routeKey));
            assertFalse(udp2rawCtxMap.containsKey(firstRelay));
            assertFalse(udp2rawCtxMap.containsKey(replenishedRelay));
            assertSame(context, udp2rawCtxMap.get(staleRelay), "udp2raw session cleanup 只清理传入快照内的 relayAddr");
            assertFalse(udp2rawRouteMap.containsKey(routeKey));
            assertNull(relay.attr(SocksUdpRelayHandler.ATTR_LAST_ROUTE).get());
        } finally {
            relay.finishAndReleaseAll();
        }
    }

    private static DatagramPacket socks5Packet(InetSocketAddress clientAddr, String payload) {
        ByteBuf packetBuf = Unpooled.buffer();
        packetBuf.writeZero(3);
        packetBuf.writeByte(0x01);
        packetBuf.writeBytes(new byte[]{127, 0, 0, 1});
        packetBuf.writeShort(15302);
        packetBuf.writeBytes(payload.getBytes(StandardCharsets.UTF_8));
        return new DatagramPacket(packetBuf, new InetSocketAddress("127.0.0.1", 1080), clientAddr);
    }

    private static DatagramPacket readOutbound(EmbeddedChannel relay) throws InterruptedException {
        DatagramPacket outbound = null;
        long deadline = System.currentTimeMillis() + 3000L;
        while (System.currentTimeMillis() < deadline && outbound == null) {
            relay.runPendingTasks();
            relay.runScheduledPendingTasks();
            outbound = relay.readOutbound();
            if (outbound == null) {
                Thread.sleep(20L);
            }
        }
        return outbound;
    }
}
