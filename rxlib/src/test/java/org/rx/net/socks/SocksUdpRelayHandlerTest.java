package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.socket.DatagramPacket;
import org.junit.jupiter.api.Test;
import org.rx.net.socks.upstream.Upstream;
import org.rx.net.support.UnresolvedEndpoint;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

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
}
