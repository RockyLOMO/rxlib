package org.rx.net.socks.upstream;

import org.junit.jupiter.api.Test;
import org.rx.net.socks.Socks5Client;
import org.rx.net.socks.SocksConfig;
import org.rx.net.socks.UdpPortHoppingMode;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SocksUdpUpstreamPortHoppingTest {
    @Test
    void sessionGroupRoundRobinSelectsActiveRelayAddresses() {
        InetSocketAddress first = new InetSocketAddress("127.0.0.1", 21001);
        InetSocketAddress second = new InetSocketAddress("127.0.0.1", 21002);
        InetSocketAddress third = new InetSocketAddress("127.0.0.1", 21003);
        SocksUdpUpstream.SessionGroup group = new SocksUdpUpstream.SessionGroup(new SocksUdpUpstream.SessionHolder[]{
                holder(first, false),
                holder(second, false),
                holder(third, false)
        }, UdpPortHoppingMode.ROUND_ROBIN);

        assertEquals(first, group.selectRelayAddress());
        assertEquals(second, group.selectRelayAddress());
        assertEquals(third, group.selectRelayAddress());
        assertEquals(first, group.selectRelayAddress());
        assertTrue(group.containsRelayAddress(second));
        assertArrayEquals(new InetSocketAddress[]{first, second, third}, group.snapshotRelayAddresses());
    }

    @Test
    void sessionGroupSkipsClosedRelayAddress() {
        InetSocketAddress closed = new InetSocketAddress("127.0.0.1", 22001);
        InetSocketAddress active = new InetSocketAddress("127.0.0.1", 22002);
        SocksUdpUpstream.SessionGroup group = new SocksUdpUpstream.SessionGroup(new SocksUdpUpstream.SessionHolder[]{
                holder(closed, true),
                holder(active, false)
        }, UdpPortHoppingMode.ROUND_ROBIN);

        assertEquals(active, group.selectRelayAddress());
        assertEquals(1, group.activeCount());
        assertFalse(group.containsRelayAddress(closed));
        assertArrayEquals(new InetSocketAddress[]{active}, group.snapshotRelayAddresses());
    }

    @Test
    void adaptiveSessionGroupScalesByTrafficThresholdStep() {
        InetSocketAddress first = new InetSocketAddress("127.0.0.1", 23001);
        InetSocketAddress second = new InetSocketAddress("127.0.0.1", 23002);
        SocksUdpUpstream.SessionGroup group = new SocksUdpUpstream.SessionGroup(new SocksUdpUpstream.SessionHolder[]{
                holder(first, false)
        }, UdpPortHoppingMode.ROUND_ROBIN, true, 100L, 0L, 0);

        long now = group.createdAtMillis;
        group.recordBytes(99);
        assertFalse(group.tryBeginScaleUp(now));
        group.recordBytes(1);
        assertTrue(group.tryBeginScaleUp(now));
        assertFalse(group.tryBeginScaleUp(now));

        group.addHolder(holder(second, false));
        group.finishScaleUp(true, now);
        assertEquals(2, group.holderCount());
        assertFalse(group.tryBeginScaleUp(now));
        group.recordBytes(100);
        assertTrue(group.tryBeginScaleUp(now));
    }

    @Test
    void adaptiveSessionGroupScalesByActiveTimeThresholdStep() {
        InetSocketAddress first = new InetSocketAddress("127.0.0.1", 24001);
        SocksUdpUpstream.SessionGroup group = new SocksUdpUpstream.SessionGroup(new SocksUdpUpstream.SessionHolder[]{
                holder(first, false)
        }, UdpPortHoppingMode.ROUND_ROBIN, true, 0L, 1000L, 0);

        long created = group.createdAtMillis;
        assertFalse(group.tryBeginScaleUp(created + 999L));
        assertTrue(group.tryBeginScaleUp(created + 1000L));
        group.finishScaleUp(true, created + 1000L);
        assertFalse(group.tryBeginScaleUp(created + 1500L));
        assertTrue(group.tryBeginScaleUp(created + 2000L));
    }

    @Test
    void adaptivePortHoppingConfigEnablesSingleHopStart() {
        SocksConfig config = new SocksConfig();
        config.setUdpPortHoppingEnabled(true);
        config.setUdpPortHoppingAdaptive(true);
        config.setUdpPortHoppingMinHopCount(1);
        config.setUdpPortHoppingMaxHopCount(4);
        config.setUdpPortHoppingAdaptiveScaleUpBytes(4096L);

        assertTrue(config.isUdpPortHoppingEnabled());
        assertTrue(config.isUdpPortHoppingAdaptive());
        assertEquals(1, config.getUdpPortHoppingMinHopCount());
        assertEquals(4, config.getUdpPortHoppingMaxHopCount());
        assertEquals(4096L, config.getUdpPortHoppingAdaptiveScaleUpBytes());
    }

    private static SocksUdpUpstream.SessionHolder holder(InetSocketAddress relayAddr, boolean closed) {
        Socks5Client.Socks5UdpSession session = mock(Socks5Client.Socks5UdpSession.class);
        when(session.isClosed()).thenReturn(closed);
        return new SocksUdpUpstream.SessionHolder(null, session, null, null, relayAddr, false);
    }
}
