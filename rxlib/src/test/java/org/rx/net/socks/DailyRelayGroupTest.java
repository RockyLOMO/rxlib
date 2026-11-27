package org.rx.net.socks;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.local.LocalAddress;
import org.junit.jupiter.api.Test;
import org.rx.net.rss.udp.RssDatagramHandler;
import org.rx.net.udp.UdpRedundantEncoder;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.Sockets;
import org.rx.net.socks.upstream.SocksUdpUpstream;
import org.rx.net.support.UpstreamSupport;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class DailyRelayGroupTest {
    @Test
    void oneClientSocketKeepsDestinationPoliciesSeparate() throws Exception {
        InetSocketAddress loopback = new InetSocketAddress("127.0.0.1", 0);
        SocksConfig config = new SocksConfig(new LocalAddress(UUID.randomUUID().toString()));
        config.setDailyUdpFeatures(7);
        try (DatagramSocket target = new DatagramSocket(loopback);
             DatagramSocket ordinary = new DatagramSocket(loopback);
             SocksProxyServer server = new SocksProxyServer(config)) {
            config.setRequiredUdpCipherPorts(new int[]{target.getLocalPort()});
            UpstreamSupport next = new UpstreamSupport(new AuthenticEndpoint(loopback, null, null),
                    new SocksProxyServerIntegrationTest.LocalRpcFacade(server));
            SocksConfig clear = new SocksConfig(), second = new SocksConfig();
            clear.setDailyUdpFeatures(RssDatagramHandler.LZ4);
            second.setDailyUdpFeatures(RssDatagramHandler.FEC | (RssDatagramHandler.hasCipherKey() ? RssDatagramHandler.AES : 0));
            SocksUdpUpstream a = new SocksUdpUpstream((InetSocketAddress) ordinary.getLocalSocketAddress(), clear, next);
            SocksUdpUpstream b = new SocksUdpUpstream((InetSocketAddress) target.getLocalSocketAddress(), second, next);
            assertNotEquals(a.poolKey(), b.poolKey());
            LinkedBlockingQueue<Integer> replies = new LinkedBlockingQueue<>();
            Channel channel = Sockets.udpBootstrap(null, ch -> ch.pipeline().addLast(
                    new SimpleChannelInboundHandler<io.netty.channel.socket.DatagramPacket>() {
                        @Override protected void channelRead0(ChannelHandlerContext ctx, io.netty.channel.socket.DatagramPacket p) {
                            UdpManager.socks5Decode(p.content());
                            replies.add(p.content().readInt());
                        }
                    })).bind(loopback).sync().channel();
            try {
                java.util.concurrent.CompletableFuture.allOf(a.initChannelAsync(channel), b.initChannelAsync(channel))
                        .get(10, TimeUnit.SECONDS);
                assertNotEquals(a.getUdpRelayAddress(channel), b.getUdpRelayAddress(channel));
                target.setSoTimeout(400);
                send(channel, a, target, 1);
                assertThrows(SocketTimeoutException.class, () -> target.receive(new DatagramPacket(new byte[4], 4)));
                int seq = 0;
                for (SocksUdpUpstream up : new SocksUdpUpstream[]{a, b, a}) {
                    DatagramSocket dst = up == b && RssDatagramHandler.hasCipherKey() ? target : ordinary;
                    dst.setSoTimeout(3000);
                    send(channel, up, dst, ++seq);
                    DatagramPacket packet = new DatagramPacket(new byte[4], 4);
                    dst.receive(packet);
                    dst.send(packet);
                    assertEquals(Integer.valueOf(seq), replies.poll(3, TimeUnit.SECONDS));
                }
            } finally {
                channel.close().sync();
            }
        }
    }

    @Test
    void profileNegotiationRejectsMixedFeaturesAndExchangesAnAuthenticatedSession() throws Exception {
        SocksConfig config = new SocksConfig(new LocalAddress(UUID.randomUUID().toString()));
        config.setDailyUdpFeatures(31);
        try (SocksProxyServer server = new SocksProxyServer(config)) {
            String token = SocksRpcContract.rpcToken();
            assertTrue(server.socksRpcCapabilities(token).has(SocksRpcCapabilities.PROFILE_UDP_V1));
            UdpRelayGroupOpenRequest req = new UdpRelayGroupOpenRequest();
            req.setMaxRelayCount(2);
            req.setDailyFeatures(RssDatagramHandler.GAME);
            assertFalse(server.openUdpRelayGroup(req, token).isSuccess());
            req.setProfileVersion(1);
            req.setClientSalt(UUID.randomUUID());
            req.setDailyFeatures(RssDatagramHandler.GAME | RssDatagramHandler.FEC | RssDatagramHandler.WEAK);
            assertFalse(server.openUdpRelayGroup(req, token).isSuccess());
            req.setDailyFeatures(RssDatagramHandler.WEAK);
            assertFalse(server.openUdpRelayGroup(req, token).isSuccess());
            req.setDailyFeatures(RssDatagramHandler.GAME | RssDatagramHandler.FEC);
            assertFalse(server.openUdpRelayGroup(req, token).isSuccess());
            for (int features : new int[]{RssDatagramHandler.GAME, RssDatagramHandler.WEAK | RssDatagramHandler.FEC}) {
                req.setDailyFeatures(features);
                UdpRelayGroupOpenResult result = server.openUdpRelayGroup(req, token);
                assertTrue(result.isSuccess(), result.getErrorCode());
                assertEquals(1, result.getProfileVersion());
                assertEquals(features, result.getSelectedFeatures());
                assertEquals(32, result.getAuthSecret().length);
                assertFalse(com.alibaba.fastjson2.JSON.toJSONString(result).contains("authSecret"));
                assertTrue(server.closeUdpRelayGroup(result.getGroupId(), token));
            }
        }
    }

    private static void send(Channel channel, SocksUdpUpstream route, DatagramSocket dst, int value) throws Exception {
        channel.writeAndFlush(new io.netty.channel.socket.DatagramPacket(
                UdpManager.socks5Encode(channel.alloc().buffer(4).writeInt(value), (InetSocketAddress) dst.getLocalSocketAddress()),
                route.getUdpRelayAddress(channel))).sync();
    }

    @Test
    void negotiationIsPerGroupAndClosingEitherPortClosesItsPartner() throws Exception {
        SocksConfig config = new SocksConfig(new LocalAddress(UUID.randomUUID().toString()));
        config.setDailyUdpFeatures(7);
        assertThrows(IllegalArgumentException.class, () -> config.setDailyPortRange("66000-66001"));
        assertThrows(IllegalArgumentException.class, () -> config.setDailyPortRange("15680-15680"));
        config.setDailyPortRange("15678-15681");
        config.setUdpRedundantMultiplier(2);
        try (SocksProxyServer server = new SocksProxyServer(config)) {
            UdpRelayGroupOpenRequest request = new UdpRelayGroupOpenRequest();
            String rpcToken = SocksRpcContract.rpcToken();
            assertTrue(server.socksRpcCapabilities(rpcToken).has(SocksRpcCapabilities.SUPPORT_DAILY_UDP));
            UdpRelayGroupOpenResult legacy = server.openUdpRelayGroup(request, rpcToken);
            assertTrue(legacy.isSuccess());
            assertEquals(rpcToken, legacy.getToken());
            assertEquals(1, legacy.getRelays().size());

            request.setMaxRelayCount(2);
            request.setDailyFeatures(RssDatagramHandler.FEC);
            request.setUdpMtu(255);
            assertEquals("INVALID_UDP_MTU", server.openUdpRelayGroup(request, rpcToken).getErrorCode());
            request.setUdpMtu(256);
            UdpRelayGroupOpenResult daily = server.openUdpRelayGroup(request, rpcToken);
            assertTrue(daily.isSuccess(), daily.getErrorCode());
            assertEquals(256, daily.getUdpMtu());
            assertNotEquals(rpcToken, daily.getToken());
            assertNotNull(UUID.fromString(daily.getToken()));
            assertEquals(2, daily.getRelays().size());
            UdpRelayEndpoint hop = daily.getRelays().get(1);
            assertEquals(SocksRpcCapabilities.DAILY_HOP_ID, hop.getRelayId());
            Channel primary = server.udpRelayRegistry.get(daily.getRelays().get(0).getRelayAddress().getPort());
            Channel alias = server.udpRelayRegistry.get(hop.getRelayAddress().getPort());
            assertEquals(15680 + 15681, daily.getRelays().get(0).getRelayAddress().getPort()
                    + hop.getRelayAddress().getPort());
            assertFalse(server.openUdpRelayGroup(request, rpcToken).isSuccess());
            assertNotSame(primary, alias);
            assertSame(primary.eventLoop(), alias.eventLoop());
            assertNotNull(primary.pipeline().get(RssDatagramHandler.class));
            assertNull(primary.pipeline().get(UdpRedundantEncoder.class));
            assertNull(alias.pipeline().get(SocksUdpRelayHandler.class));
            alias.close().sync();
            assertTrue(primary.closeFuture().await(5, TimeUnit.SECONDS));
            assertFalse(server.heartbeatUdpRelayGroup(daily.getGroupId(), rpcToken));
            assertTrue(server.heartbeatUdpRelayGroup(legacy.getGroupId(), rpcToken));
        }
    }
}
