package org.rx.net.transport.hybrid;

import io.netty.buffer.PooledByteBufAllocator;

import java.util.concurrent.atomic.LongAdder;

public final class HybridMetrics {
    final LongAdder tcpSendPackets = new LongAdder();
    final LongAdder udpSendPackets = new LongAdder();
    final LongAdder tcpReceivePackets = new LongAdder();
    final LongAdder udpReceivePackets = new LongAdder();
    final LongAdder tcpSendBytes = new LongAdder();
    final LongAdder udpSendBytes = new LongAdder();
    final LongAdder tcpPendingDrops = new LongAdder();
    final LongAdder udpAckTimeouts = new LongAdder();
    final LongAdder udpWriteDrops = new LongAdder();
    final LongAdder udpFallbackToTcp = new LongAdder();
    final LongAdder duplicateDrops = new LongAdder();
    final LongAdder illegalUdpDrops = new LongAdder();
    final LongAdder routeSwitches = new LongAdder();
    final LongAdder activeSessions = new LongAdder();
    final LongAdder tcpOnlySessions = new LongAdder();
    final LongAdder udpProbingSessions = new LongAdder();
    final LongAdder udpPunchingSessions = new LongAdder();
    final LongAdder udpReadySessions = new LongAdder();

    public long tcpSendPackets() {
        return tcpSendPackets.sum();
    }

    public long udpSendPackets() {
        return udpSendPackets.sum();
    }

    public long tcpReceivePackets() {
        return tcpReceivePackets.sum();
    }

    public long udpReceivePackets() {
        return udpReceivePackets.sum();
    }

    public long tcpSendBytes() {
        return tcpSendBytes.sum();
    }

    public long udpSendBytes() {
        return udpSendBytes.sum();
    }

    public long tcpPendingDrops() {
        return tcpPendingDrops.sum();
    }

    public long udpAckTimeouts() {
        return udpAckTimeouts.sum();
    }

    public long udpWriteDrops() {
        return udpWriteDrops.sum();
    }

    public long udpFallbackToTcp() {
        return udpFallbackToTcp.sum();
    }

    public long duplicateDrops() {
        return duplicateDrops.sum();
    }

    public long illegalUdpDrops() {
        return illegalUdpDrops.sum();
    }

    public long routeSwitches() {
        return routeSwitches.sum();
    }

    public long activeSessions() {
        return activeSessions.sum();
    }

    public long tcpOnlySessions() {
        return tcpOnlySessions.sum();
    }

    public long udpProbingSessions() {
        return udpProbingSessions.sum();
    }

    public long udpPunchingSessions() {
        return udpPunchingSessions.sum();
    }

    public long udpReadySessions() {
        return udpReadySessions.sum();
    }

    public long usedDirectMemory() {
        return PooledByteBufAllocator.DEFAULT.metric().usedDirectMemory();
    }

    void sessionOpened(HybridRouteState state) {
        activeSessions.increment();
        incrementRouteState(state);
    }

    void sessionClosed(HybridRouteState state) {
        activeSessions.decrement();
        decrementRouteState(state);
    }

    void routeStateChanged(HybridRouteState oldState, HybridRouteState newState) {
        if (oldState == newState) {
            return;
        }
        routeSwitches.increment();
        decrementRouteState(oldState);
        incrementRouteState(newState);
    }

    void incrementRouteState(HybridRouteState state) {
        if (state == null) {
            return;
        }
        switch (state) {
            case TCP_ONLY:
                tcpOnlySessions.increment();
                return;
            case UDP_PROBING:
                udpProbingSessions.increment();
                return;
            case UDP_PUNCHING:
                udpPunchingSessions.increment();
                return;
            case UDP_READY:
                udpReadySessions.increment();
                return;
            default:
        }
    }

    void decrementRouteState(HybridRouteState state) {
        if (state == null) {
            return;
        }
        switch (state) {
            case TCP_ONLY:
                tcpOnlySessions.decrement();
                return;
            case UDP_PROBING:
                udpProbingSessions.decrement();
                return;
            case UDP_PUNCHING:
                udpPunchingSessions.decrement();
                return;
            case UDP_READY:
                udpReadySessions.decrement();
                return;
            default:
        }
    }
}
