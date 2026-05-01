package org.rx.net.socks.upstream;

import org.junit.jupiter.api.Test;
import org.rx.net.socks.Socks5Client;
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

    private static SocksUdpUpstream.SessionHolder holder(InetSocketAddress relayAddr, boolean closed) {
        Socks5Client.Socks5UdpSession session = mock(Socks5Client.Socks5UdpSession.class);
        when(session.isClosed()).thenReturn(closed);
        return new SocksUdpUpstream.SessionHolder(null, session, null, null, relayAddr, false);
    }
}
