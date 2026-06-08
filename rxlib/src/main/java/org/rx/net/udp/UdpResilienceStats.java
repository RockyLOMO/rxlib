package org.rx.net.udp;

import java.util.concurrent.atomic.LongAdder;

/**
 * UDP Resilience 轻量统计。调用方可周期性读取后接入指标系统。
 */
public class UdpResilienceStats {
    private final LongAdder resilienceDataPackets = new LongAdder();
    private final LongAdder parityPackets = new LongAdder();
    private final LongAdder redundantCopies = new LongAdder();
    private final LongAdder receivedResiliencePackets = new LongAdder();
    private final LongAdder deliveredPackets = new LongAdder();
    private final LongAdder recoveredPackets = new LongAdder();
    private final LongAdder duplicateDrops = new LongAdder();
    private final LongAdder decodeDrops = new LongAdder();
    private final LongAdder tooLargePackets = new LongAdder();
    private final LongAdder peerLimitDrops = new LongAdder();
    private final LongAdder groupLimitDrops = new LongAdder();

    public void recordResilienceData() {
        resilienceDataPackets.increment();
    }

    public void recordParity() {
        parityPackets.increment();
    }

    public void recordRedundantCopy() {
        redundantCopies.increment();
    }

    public void recordReceivedResilience() {
        receivedResiliencePackets.increment();
    }

    public void recordDelivered() {
        deliveredPackets.increment();
    }

    public void recordRecovered() {
        recoveredPackets.increment();
    }

    public void recordDuplicateDrop() {
        duplicateDrops.increment();
    }

    public void recordDecodeDrop() {
        decodeDrops.increment();
    }

    public void recordTooLarge() {
        tooLargePackets.increment();
    }

    public void recordPeerLimitDrop() {
        peerLimitDrops.increment();
    }

    public void recordGroupLimitDrop() {
        groupLimitDrops.increment();
    }

    public long resilienceDataPackets() {
        return resilienceDataPackets.sum();
    }

    public long parityPackets() {
        return parityPackets.sum();
    }

    public long redundantCopies() {
        return redundantCopies.sum();
    }

    public long receivedResiliencePackets() {
        return receivedResiliencePackets.sum();
    }

    public long deliveredPackets() {
        return deliveredPackets.sum();
    }

    public long recoveredPackets() {
        return recoveredPackets.sum();
    }

    public long duplicateDrops() {
        return duplicateDrops.sum();
    }

    public long decodeDrops() {
        return decodeDrops.sum();
    }

    public long tooLargePackets() {
        return tooLargePackets.sum();
    }

    public long peerLimitDrops() {
        return peerLimitDrops.sum();
    }

    public long groupLimitDrops() {
        return groupLimitDrops.sum();
    }
}
