package org.rx.net.punch;

import org.junit.jupiter.api.Test;
import org.rx.exception.InvalidException;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UdpHolePunchRegistryTest {
    @Test
    void registerMatchesPeersAndCleanupExpiresRoom() {
        UdpHolePunchRegistry registry = new UdpHolePunchRegistry(1000, 2);
        InetSocketAddress peerA = new InetSocketAddress("127.0.0.1", 30001);
        InetSocketAddress peerB = new InetSocketAddress("127.0.0.1", 30002);

        UdpHolePunchRegistry.Snapshot first = registry.register("room-a", "peer-a", peerA, 100L);
        assertTrue(first.waiting);
        assertEquals(peerA, first.observedEndpoint);
        assertNull(first.peerId);
        assertNull(first.peerEndpoint);

        UdpHolePunchRegistry.Snapshot second = registry.register("room-a", "peer-b", peerB, 200L);
        assertFalse(second.waiting);
        assertEquals("peer-a", second.peerId);
        assertEquals(peerA, second.peerEndpoint);

        UdpHolePunchRegistry.Snapshot third = registry.register("room-a", "peer-a", peerA, 300L);
        assertFalse(third.waiting);
        assertEquals("peer-b", third.peerId);
        assertEquals(peerB, third.peerEndpoint);

        assertEquals(1, registry.roomCount());
        assertEquals(2, registry.peerCount("room-a"));

        registry.cleanup(1500L);
        assertEquals(0, registry.roomCount());
        assertEquals(0, registry.peerCount("room-a"));
    }

    @Test
    void registerRejectsThirdPeerWhenRoomIsFull() {
        UdpHolePunchRegistry registry = new UdpHolePunchRegistry(5000, 2);
        registry.register("room-b", "peer-a", new InetSocketAddress("127.0.0.1", 31001), 1L);
        registry.register("room-b", "peer-b", new InetSocketAddress("127.0.0.1", 31002), 2L);

        assertThrows(InvalidException.class, () ->
                registry.register("room-b", "peer-c", new InetSocketAddress("127.0.0.1", 31003), 3L));
    }
}
