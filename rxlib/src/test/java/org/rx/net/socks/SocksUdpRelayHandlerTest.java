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
import org.rx.net.support.UpstreamSupport;
import org.rx.net.support.UnresolvedEndpoint;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
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
