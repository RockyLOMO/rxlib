package org.rx.net.transport.hybrid;

import io.netty.buffer.PooledByteBufAllocator;

import java.util.concurrent.atomic.LongAdder;

public final class HybridMetrics {
    final LongAdder tcpSendPackets = new LongAdder();
    final LongAdder udpSendPackets = new LongAdder();
    final LongAdder tcpReceivePackets = new LongAdder();
    final LongAdder udpReceivePackets = new LongAdder();
    final LongAdder udpAckTimeouts = new LongAdder();
    final LongAdder udpWriteDrops = new LongAdder();
    final LongAdder udpFallbackToTcp = new LongAdder();
    final LongAdder duplicateDrops = new LongAdder();
    final LongAdder illegalUdpDrops = new LongAdder();

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

    public long usedDirectMemory() {
        return PooledByteBufAllocator.DEFAULT.metric().usedDirectMemory();
    }
}
