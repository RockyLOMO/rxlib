package org.rx.net.transport.hybrid;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HybridRoutePolicyTest {
    private final HybridRoutePolicy policy = new HybridRoutePolicy();

    @Test
    void tcpOnlyAlwaysRoutesTcp() {
        assertEquals(HybridRoute.TCP, policy.select(HybridRouteState.TCP_ONLY, 64, 1024, HybridSendOptions.DEFAULT));
    }

    @Test
    void udpReadySmallPacketRoutesUdp() {
        assertEquals(HybridRoute.UDP, policy.select(HybridRouteState.UDP_READY, 64, 1024, HybridSendOptions.DEFAULT));
    }

    @Test
    void udpReadyLargePacketRoutesTcp() {
        assertEquals(HybridRoute.TCP, policy.select(HybridRouteState.UDP_READY, 2048, 1024, HybridSendOptions.DEFAULT));
    }

    @Test
    void forceTcpWins() {
        assertEquals(HybridRoute.TCP, policy.select(HybridRouteState.UDP_READY, 64, 1024, HybridSendOptions.FORCE_TCP));
    }
}
