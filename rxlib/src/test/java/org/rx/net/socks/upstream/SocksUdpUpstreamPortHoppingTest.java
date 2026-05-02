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
    void sessionGroupRemovesHolderAndKeepsReplenishTarget() {
        InetSocketAddress first = new InetSocketAddress("127.0.0.1", 25001);
        InetSocketAddress second = new InetSocketAddress("127.0.0.1", 25002);
        InetSocketAddress third = new InetSocketAddress("127.0.0.1", 25003);
        SocksUdpUpstream.SessionHolder firstHolder = holder(first, false);
        SocksUdpUpstream.SessionHolder secondHolder = holder(second, false);
        SocksUdpUpstream.SessionHolder thirdHolder = holder(third, false);
        SocksUdpUpstream.SessionGroup group = new SocksUdpUpstream.SessionGroup(new SocksUdpUpstream.SessionHolder[]{
                firstHolder,
                secondHolder,
                thirdHolder
        }, UdpPortHoppingMode.ROUND_ROBIN, 3);

        assertTrue(group.removeHolder(secondHolder));
        assertEquals(2, group.holderCount());
        assertEquals(2, group.activeCount());
        assertTrue(group.needsReplenish());
        assertFalse(group.shouldInvalidate(2));
        assertTrue(group.shouldInvalidate(3));
        assertFalse(group.containsRelayAddress(second));
        assertArrayEquals(new InetSocketAddress[]{first, third}, group.snapshotRelayAddresses());

        group.addHolder(holder(second, false));
        assertEquals(3, group.holderCount());
        assertFalse(group.needsReplenish());
    }

    @Test
    void sessionGroupReplenishUsesCooldown() {
        InetSocketAddress first = new InetSocketAddress("127.0.0.1", 26001);
        SocksUdpUpstream.SessionGroup group = new SocksUdpUpstream.SessionGroup(new SocksUdpUpstream.SessionHolder[]{
                holder(first, false)
        }, UdpPortHoppingMode.ROUND_ROBIN, 2);

        long now = group.createdAtMillis;
        assertTrue(group.needsReplenish());
        assertTrue(group.tryBeginReplenish(now, 1000));
        assertFalse(group.tryBeginReplenish(now + 1, 1000));
        group.finishReplenish(false, now + 1);
        assertFalse(group.tryBeginReplenish(now + 500, 1000));
        assertTrue(group.tryBeginReplenish(now + 1001, 1000));
    }

    @Test
    void adaptivePortHoppingConfigEnablesSingleHopStart() {
        SocksConfig config = new SocksConfig();
        config.setUdpPortHoppingEnabled(true);
        config.setUdpPortHoppingAdaptive(true);
        config.setUdpPortHoppingMinHopCount(1);
        config.setUdpPortHoppingMaxHopCount(4);
        config.setUdpPortHoppingAdaptiveScaleUpBytes(4096L);
        config.setUdpPortHoppingReplenishDelayMillis(1500);

        assertTrue(config.isUdpPortHoppingEnabled());
        assertTrue(config.isUdpPortHoppingAdaptive());
        assertEquals(1, config.getUdpPortHoppingMinHopCount());
        assertEquals(4, config.getUdpPortHoppingMaxHopCount());
        assertEquals(4096L, config.getUdpPortHoppingAdaptiveScaleUpBytes());
        assertEquals(1500, config.getUdpPortHoppingReplenishDelayMillis());
    }

    private static SocksUdpUpstream.SessionHolder holder(InetSocketAddress relayAddr, boolean closed) {
        Socks5Client.Socks5UdpSession session = mock(Socks5Client.Socks5UdpSession.class);
        when(session.isClosed()).thenReturn(closed);
        return new SocksUdpUpstream.SessionHolder(null, session, null, null, relayAddr, false);
    }
}
