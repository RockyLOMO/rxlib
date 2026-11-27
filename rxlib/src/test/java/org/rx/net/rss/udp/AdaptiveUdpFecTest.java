package org.rx.net.rss.udp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class AdaptiveUdpFecTest {
    static class Link implements AutoCloseable {
        final AtomicLong time = new AtomicLong();
        final AdaptiveUdpFec sender = new AdaptiveUdpFec(UnpooledByteBufAllocator.DEFAULT, time::get);
        final AdaptiveUdpFec receiver = new AdaptiveUdpFec(UnpooledByteBufAllocator.DEFAULT, time::get);
        final ArrayDeque<ByteBuf> packets = new ArrayDeque<>();
        final ArrayDeque<Integer> delivered = new ArrayDeque<>();
        int fixedMissing = -1; // Optional controlled feedback for fixed-tier coverage measurements.

        void send(int value) {
            ByteBuf payload = Unpooled.buffer().writeInt(value).writeZero(value % 11);
            try {
                sender.write(payload, this::queue);
                assertEquals(0, payload.readerIndex());
            } finally {
                payload.release();
            }
        }

        void queue(ByteBuf frame) {
            assertEquals(AdaptiveUdpFec.WIRE_HEADROOM, frame.readerIndex());
            packets.add(frame);
        }

        void receive(ByteBuf frame) {
            try {
                receiver.read(frame, payload -> {
                    try {
                        int value = payload.readInt();
                        assertEquals(value % 11, payload.readableBytes());
                        delivered.add(value);
                    } finally {
                        payload.release();
                    }
                }, report -> {
                    try {
                        assertEquals(AdaptiveUdpFec.WIRE_HEADROOM, report.readerIndex());
                        if (fixedMissing >= 0) report.setByte(report.readerIndex() + 5, fixedMissing);
                        sender.read(report, unused -> unused.release(), unused -> unused.release());
                    } finally {
                        report.release();
                    }
                });
            } finally {
                frame.release();
            }
        }

        void drain() {
            while (!packets.isEmpty()) {
                receive(packets.remove());
            }
        }

        void introduceLoss() {
            introduceLoss(0);
        }

        void introduceLoss(long packetInterval) {
            for (int i = 0; i < 40; i++) {
                time.addAndGet(packetInterval);
                send(i);
                ByteBuf wire = packets.remove();
                assertEquals(AdaptiveUdpFec.DATA, wire.getByte(wire.readerIndex()));
                if (i >= 12 && i < 16) {
                    wire.release();
                } else {
                    receive(wire);
                }
            }
            delivered.clear();
        }

        public void close() {
            sender.close();
            receiver.close();
            while (!packets.isEmpty()) {
                packets.remove().release();
            }
            assertEquals(-1, sender.nextDelayMillis());
            assertEquals(-1, receiver.nextDelayMillis());
        }
    }

    @Test
    void lateParityForCompleteGroupReleasesAccumulator() {
        AtomicLong clock = new AtomicLong();
        try (AdaptiveUdpFec fec = new AdaptiveUdpFec(UnpooledByteBufAllocator.DEFAULT, clock::get)) {
            AtomicLong deliveries = new AtomicLong();
            for (int index = 0; index < 3; index++) {
                ByteBuf wire = Unpooled.buffer();
                if (index < 2) wire.writeByte(AdaptiveUdpFec.SHARD).writeInt(index).writeByte(index).writeByte(7);
                else wire.writeByte(AdaptiveUdpFec.PARITY).writeInt(0).writeByte(2).writeShort(0).writeByte(0);
                try {
                    fec.read(wire, payload -> { deliveries.incrementAndGet(); payload.release(); }, ByteBuf::release);
                } finally {
                    wire.release();
                }
            }
            assertEquals(2, deliveries.get());
            assertEquals(0, fec.recoveredPackets());
            assertEquals(-1, fec.nextDelayMillis());
        }
    }

    @Test
    void reusedAccumulatorsIsolateRetainedRecoveriesAndCountUnprotectedGroups() {
        AtomicLong clock = new AtomicLong();
        ArrayDeque<ByteBuf> retained = new ArrayDeque<>(), wire = new ArrayDeque<>();
        try (AdaptiveUdpFec tx = new AdaptiveUdpFec(UnpooledByteBufAllocator.DEFAULT, clock::get);
             AdaptiveUdpFec rx = new AdaptiveUdpFec(UnpooledByteBufAllocator.DEFAULT, clock::get)) {
            ByteBuf input = Unpooled.buffer(16);
            try {
                for (int i = 0; i < 40; i++) {
                    clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(50));
                    input.clear().writeInt(i);
                    tx.write(input, wire::add);
                    ByteBuf f = wire.remove();
                    if (i == 12) f.release();
                    else {
                        try {
                            rx.read(f, ByteBuf::release, r -> {
                                try { tx.read(r, ByteBuf::release, wire::add); } finally { r.release(); }
                            });
                        } finally {
                            f.release();
                        }
                    }
                }
                // First sparse 4-shard group at 50ms has credit 4 < 16: flushed without parity.
                for (int i = 40; i < 44; i++) {
                    clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(50));
                    input.clear().writeInt(i);
                    tx.write(input, wire::add);
                    ByteBuf f = wire.remove();
                    try { rx.read(f, ByteBuf::release, ByteBuf::release); } finally { f.release(); }
                }
                clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(300));
                tx.tick(wire::add);
                rx.tick(wire::add);
                assertEquals(1, tx.unprotectedTxGroups);
                assertEquals(1, rx.unprotectedRxGroups);

                // Burst two full K=16 groups, dropping one shard in each and holding recovered slices open.
                for (int round = 0; round < 2; round++) {
                    final int expected = 100 + round * 16 + 5;
                    for (int i = 0; i < 16; i++) {
                        int val = 100 + round * 16 + i;
                        input.clear().writeInt(val).writeLong(0x1122334455667788L ^ val);
                        tx.write(input, wire::add);
                        while (!wire.isEmpty()) {
                            ByteBuf f = wire.remove();
                            if (f.getUnsignedByte(f.readerIndex()) == AdaptiveUdpFec.SHARD && i == 5) {
                                f.release();
                            } else {
                                try {
                                    rx.read(f, payload -> {
                                        if (payload.readInt() == expected) retained.add(payload);
                                        else payload.release();
                                    }, ByteBuf::release);
                                } finally {
                                    f.release();
                                }
                            }
                        }
                    }
                }
                assertEquals(2, retained.size());
                ByteBuf firstRecovered = retained.remove();
                ByteBuf secondRecovered = retained.remove();
                try {
                    assertEquals(0x1122334455667788L ^ 105, firstRecovered.readLong());
                    assertEquals(0x1122334455667788L ^ 121, secondRecovered.readLong());
                } finally {
                    firstRecovered.release();
                    secondRecovered.release();
                }
                clock.addAndGet(TimeUnit.SECONDS.toNanos(6));
                tx.tick(ByteBuf::release);
                rx.tick(ByteBuf::release);
                assertEquals(-1, tx.nextDelayMillis());
                assertEquals(-1, rx.nextDelayMillis());
            } finally {
                input.release();
            }
        } finally {
            while (!retained.isEmpty()) retained.remove().release();
            while (!wire.isEmpty()) wire.remove().release();
        }
    }

    @Test
    void weakLowPpsPairsSustainFeedbackWithoutIdleHeartbeats() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        ArrayDeque<ByteBuf> q = new ArrayDeque<>(), rq = new ArrayDeque<>();
        ByteBuf payload = Unpooled.buffer(32).writeZero(32);
        try (AdaptiveUdpFec tx = new AdaptiveUdpFec(UnpooledByteBufAllocator.DEFAULT, clock::get, true, 44);
             AdaptiveUdpFec rx = new AdaptiveUdpFec(UnpooledByteBufAllocator.DEFAULT, clock::get, true, 44)) {
            // 2 PPS in 20ms pairs (980ms gap between pairs): 32 packets take 16s (> 5s REPORT_TIMEOUT),
            // so activity-gated 3s weak reports must sustain target and recover paired losses.
            for (int i = 0; i < 80; i++) {
                long step = (i & 1) == 1 ? TimeUnit.MILLISECONDS.toNanos(20) : TimeUnit.MILLISECONDS.toNanos(980);
                long due = tx.nextDelayMillis();
                if (due >= 0 && TimeUnit.MILLISECONDS.toNanos(due) <= step) {
                    clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(due));
                    tx.tick(q::add);
                    while (!q.isEmpty()) {
                        ByteBuf f = q.remove();
                        try { rx.read(f, ByteBuf::release, rq::add); } finally { f.release(); }
                    }
                    clock.addAndGet(step - TimeUnit.MILLISECONDS.toNanos(due));
                } else {
                    clock.addAndGet(step);
                }
                rx.tick(rq::add);
                tx.write(payload, q::add);
                while (!q.isEmpty()) {
                    ByteBuf f = q.remove();
                    if (f.getUnsignedByte(f.readerIndex()) != AdaptiveUdpFec.PARITY && i % 20 == 6) {
                        f.release();
                    } else {
                        try { rx.read(f, ByteBuf::release, rq::add); } finally { f.release(); }
                    }
                }
                while (!rq.isEmpty()) {
                    ByteBuf r = rq.remove();
                    try { tx.read(r, ByteBuf::release, q::add); } finally { r.release(); }
                }
            }
            clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(200));
            tx.tick(q::add);
            while (!q.isEmpty()) {
                ByteBuf f = q.remove();
                try { rx.read(f, ByteBuf::release, rq::add); } finally { f.release(); }
            }
            assertTrue(tx.paritySent > 0, "weak low-PPS pairs must sustain FEC parity");
            assertTrue(rx.recoveredData >= 1, "weak low-PPS pairs must recover lost shard");
            // Idle silence produces zero heartbeats or timers.
            while (!rq.isEmpty()) rq.remove().release();
            clock.addAndGet(TimeUnit.MINUTES.toNanos(5));
            tx.tick(q::add);
            rx.tick(rq::add);
            assertTrue(q.isEmpty() && rq.isEmpty());
            assertEquals(-1, tx.nextDelayMillis());
            assertEquals(-1, rx.nextDelayMillis());
        } finally {
            payload.release();
            while (!q.isEmpty()) q.remove().release();
            while (!rq.isEmpty()) rq.remove().release();
        }
    }

    @Test
    void weakChargesVariableParityAtItsEncodedByteCostAndCanCancelTail() {
        AtomicLong time = new AtomicLong();
        final int authHeader = 44;
        long[] wireBytes = new long[2];
        java.util.function.Consumer<ByteBuf> count = frame -> {
            try {
                int kind = frame.getUnsignedByte(frame.readerIndex()) == AdaptiveUdpFec.PARITY ? 1 : 0;
                wireBytes[kind] += frame.readableBytes() + authHeader;
            } finally {
                frame.release();
            }
        };
        try (AdaptiveUdpFec fec = new AdaptiveUdpFec(UnpooledByteBufAllocator.DEFAULT, time::get, true, authHeader)) {
            ByteBuf small = Unpooled.buffer(10).writeZero(10), large = Unpooled.buffer(1400).writeZero(1400);
            try {
                for (int i = 0; i < 40; i++) fec.write(small, count);
                assertEquals(0, fec.paritySent);
                ByteBuf report = Unpooled.buffer(8).writeByte(AdaptiveUdpFec.REPORT).writeInt(39)
                        .writeByte(4).writeByte(4).writeByte(0);
                try {
                    fec.read(report, count, count);
                } finally {
                    report.release();
                }
                long afterWarmup = wireBytes[0];
                for (int i = 0; i < 320; i++) {
                    if (i % 8 == 0) fec.writeTailProbe(large, count);
                    else fec.write(i % 4 == 0 ? large : small, count);
                    assertTrue(wireBytes[1] * 4 <= wireBytes[0] - afterWarmup);
                }
                assertEquals(wireBytes[1], fec.parityBytes);
                assertEquals(wireBytes[0], fec.dataBytes);
                assertTrue(fec.probeGroupParityBytes() > 0);
                assertTrue(fec.probeGroupParityBytes() <= fec.parityBytes);
                fec.write(small, count);
                assertTrue(fec.nextDelayMillis() >= 0);
                fec.cancelPendingParity();
                assertEquals(-1, fec.nextDelayMillis());
            } finally {
                small.release();
                large.release();
            }
        }
    }

    @Test
    void weakEqualSizeShardsEmitFirstGroupParityAtK16AndK8() {
        AtomicLong time = new AtomicLong(1_000_000L);
        ArrayDeque<ByteBuf> wire = new ArrayDeque<>();
        try (AdaptiveUdpFec fec = new AdaptiveUdpFec(UnpooledByteBufAllocator.DEFAULT, time::get, true, 32)) {
            ByteBuf full = Unpooled.buffer(1237).writeZero(1237);
            try {
                for (int i = 0; i < 40; i++) {
                    fec.write(full, wire::add);
                    while (!wire.isEmpty()) wire.remove().release();
                }
                // Report 1 isolated loss -> weak enters target = 16
                ByteBuf r1 = Unpooled.buffer(8).writeByte(AdaptiveUdpFec.REPORT).writeInt(39)
                        .writeByte(1).writeByte(1).writeByte(0);
                try { fec.read(r1, ByteBuf::release, ByteBuf::release); } finally { r1.release(); }
                assertEquals(16, fec.currentTarget());

                // Exact 16 equal-sized shards must immediately emit 1 parity frame on the very first group!
                for (int i = 0; i < 16; i++) {
                    fec.write(full, wire::add);
                }
                int parityCount = 0;
                while (!wire.isEmpty()) {
                    ByteBuf f = wire.remove();
                    if (f.getUnsignedByte(f.readerIndex()) == AdaptiveUdpFec.PARITY) parityCount++;
                    f.release();
                }
                assertEquals(1, parityCount, "first full K=16 group of equal-size shards must emit parity");
                assertTrue(fec.parityBytes > 0);
                assertEquals(0, fec.unprotectedTxGroups());
            } finally {
                full.release();
            }
        } finally {
            while (!wire.isEmpty()) wire.remove().release();
        }
    }

    @Test
    void sparseAndFastLossyFlowsRecoverWithoutExceedingParityBudget() {
        for (int pps : new int[]{20, 50, 100, 200, 300, 1000}) {
            try (Link link = new Link()) {
                long interval = TimeUnit.SECONDS.toNanos(1) / pps;
                link.introduceLoss(interval);
                // Exercise actual timer wakes between sends, including partial groups.
                for (int seq = 40; seq < 72; seq++) {
                    long due = link.sender.nextDelayMillis();
                    if (due >= 0 && TimeUnit.MILLISECONDS.toNanos(due) < interval) {
                        link.time.addAndGet(TimeUnit.MILLISECONDS.toNanos(due));
                        link.sender.tick(link::queue);
                        link.drain();
                        link.time.addAndGet(interval - TimeUnit.MILLISECONDS.toNanos(due));
                    } else {
                        link.time.addAndGet(interval);
                    }
                    link.send(seq);
                    if (seq == 41) link.packets.remove().release();
                    link.drain();
                }
                assertTrue(link.delivered.contains(41), "pps=" + pps);
                assertEquals(32, link.delivered.size());
                assertTrue(link.sender.paritySent <= 8);
            }
        }
    }

    @Test
    void sparsePartialPairRecoversWithinLossTierBudget() {
        try (Link link = new Link()) {
            long interval = TimeUnit.MILLISECONDS.toNanos(100);
            link.introduceLoss(interval);
            for (int seq = 40; seq < 45; seq++) {
                link.time.addAndGet(interval);
                link.send(seq);
                if (seq == 43) link.packets.removeLast().release();
                link.drain();
            }
            assertEquals(1, link.receiver.recoveredData);
            assertEquals(1, link.sender.paritySent);
            assertEquals(5, link.delivered.size());
        }
    }

    @Test
    void sparsePredictionStillProtectsAnUnexpectedBurstOfLongShards() {
        try (Link link = new Link()) {
            link.fixedMissing = 1;
            link.introduceLoss(TimeUnit.MILLISECONDS.toNanos(50));
            ByteBuf payload = Unpooled.buffer(1400);
            boolean[] seen = new boolean[16];
            try {
                for (int i = 0; i < 16; i++) {
                    payload.clear().writeInt(i).writeZero(1200 + i).writeByte(77 + i);
                    link.sender.write(payload, link::queue);
                    payload.setZero(0, payload.writerIndex()); // input belongs to the caller
                    if (i == 3) link.packets.remove().release();
                    while (!link.packets.isEmpty()) {
                        ByteBuf frame = link.packets.remove();
                        try {
                            link.receiver.read(frame, data -> {
                                try {
                                    int value = data.readInt();
                                    assertFalse(seen[value]);
                                    seen[value] = true;
                                    assertEquals(1201 + value, data.readableBytes());
                                    assertEquals(77 + value, data.getUnsignedByte(data.writerIndex() - 1));
                                } finally {
                                    data.release();
                                }
                            }, ByteBuf::release);
                        } finally {
                            frame.release();
                        }
                    }
                }
                assertEquals(1, link.sender.paritySent);
                assertEquals(1, link.receiver.recoveredData);
                for (boolean value : seen) assertTrue(value);
            } finally {
                payload.release();
            }
        }
    }

    @Test
    void cachePressureEvictsWithinFixedBudget() {
        try (Link link = new Link()) {
            for (int i = 0; i < 20; i++) {
                ByteBuf shard = Unpooled.buffer().writeByte(AdaptiveUdpFec.SHARD).writeInt(i * 2)
                        .writeByte(0).writeInt(i).writeZero(i % 11);
                link.receive(shard);
                link.time.incrementAndGet();
            }
            assertEquals(20, link.delivered.size());
            assertEquals(4, link.receiver.evictedGroups);
        }
    }

    @Test
    void lateParityRecoversOnceWithinLiveGroupAcrossSequenceWrap() {
        for (int age : new int[]{80, 256}) {
            for (int elapsed : new int[]{299, 300}) {
                try (Link link = new Link()) {
                    // Two shards with identical four-byte payloads have all-zero XOR and length XOR.
                    int base = -3;
                    link.receive(Unpooled.buffer().writeByte(AdaptiveUdpFec.SHARD).writeInt(base)
                            .writeByte(0).writeInt(0));
                    link.time.addAndGet(TimeUnit.MILLISECONDS.toNanos(elapsed));
                    for (int i = 2; i <= age + 1; i++) {
                        link.receive(Unpooled.buffer().writeByte(AdaptiveUdpFec.DATA).writeInt(base + i)
                                .writeInt(0));
                    }
                    for (int i = 0; i < 2; i++) {
                        link.receive(Unpooled.buffer().writeByte(AdaptiveUdpFec.PARITY).writeInt(base)
                                .writeByte(2).writeShort(0).writeInt(0));
                    }
                    assertEquals(elapsed < 300 ? 1 : 0, link.receiver.recoveredData);
                    link.receive(Unpooled.buffer().writeByte(AdaptiveUdpFec.SHARD).writeInt(base + 1)
                            .writeByte(1).writeInt(0));
                    assertEquals(age + 2, link.delivered.size());
                }
            }
        }
    }

    @Test
    void delayedParityRecoversAllSixteenFullGroupsInReverseOrder() {
        try (Link link = new Link()) {
            long interval = TimeUnit.MILLISECONDS.toNanos(1);
            link.fixedMissing = 1;
            link.introduceLoss(interval);
            ArrayDeque<ByteBuf> delayed = new ArrayDeque<>();
            try {
                for (int seq = 40; seq < 296; seq++) {
                    link.time.addAndGet(interval);
                    link.send(seq);
                    int index = (seq - 40) & 15;
                    if (index == 15) delayed.add(link.packets.removeLast());
                    if (index == 0) link.packets.removeFirst().release();
                    link.drain();
                }
                while (!delayed.isEmpty()) link.receive(delayed.removeLast());
                assertEquals(16, link.receiver.recoveredData);
                assertEquals(256, link.delivered.size());
                assertEquals(0, link.receiver.evictedGroups);
            } finally {
                while (!delayed.isEmpty()) delayed.remove().release();
            }
        }
    }

    @Test
    void periodicLossComparedWithSingleSend() {
        int sent = 2048, dropped = 0, parity = 0;
        try (Link link = new Link()) {
            for (int seq = 0; seq < sent; seq++) {
                link.time.addAndGet(TimeUnit.MILLISECONDS.toNanos(1));
                link.send(seq);
                while (!link.packets.isEmpty()) {
                    ByteBuf frame = link.packets.remove();
                    int pos = frame.readerIndex();
                    if (frame.getUnsignedByte(pos) == AdaptiveUdpFec.PARITY) {
                        parity++;
                    } else if ((frame.getInt(pos + 1) & 31) == 10) {
                        dropped++;
                        frame.release();
                        continue;
                    }
                    link.receive(frame);
                }
            }
            assertEquals(64, dropped);
            assertEquals(sent - dropped + link.receiver.recoveredData, link.delivered.size());
            assertEquals(sent - 1, link.delivered.size());
            assertEquals(63, link.receiver.recoveredData);
            assertTrue(parity > 0 && parity <= sent / 8);
            System.out.printf("Daily FEC: inputs=%d dropped=%d parity=%d recovered=%d delivered=%d%n",
                    sent, dropped, parity, link.receiver.recoveredData, link.delivered.size());
        }
    }

    @Test
    void actualLossFeedbackEnablesParityThenCleanWindowsDisableIt() {
        try (Link link = new Link()) {
            link.introduceLoss();
            for (int i = 40; i < 44; i++) {
                link.send(i);
            }
            assertEquals(5, link.packets.size());
            ByteBuf parity = link.packets.removeLast();
            assertEquals(AdaptiveUdpFec.PARITY, parity.getByte(parity.readerIndex()));
            link.receive(parity);
            link.receive(link.packets.remove());
            ByteBuf delayed = link.packets.remove();
            try {
                link.drain();
                assertEquals(4, link.delivered.size());
                assertTrue(link.delivered.contains(41));
                link.receive(delayed.retainedDuplicate());
                assertEquals(4, link.delivered.size());
            } finally {
                delayed.release();
            }
            for (int i = 44; i < 144; i++) {
                link.send(i);
                link.drain();
            }
            link.send(144);
            assertEquals(1, link.packets.size());
            assertEquals(AdaptiveUdpFec.DATA, link.packets.element().getByte(AdaptiveUdpFec.WIRE_HEADROOM));
            assertEquals(-1, link.sender.nextDelayMillis());
        }
    }

    @Test
    void sparseTrafficDoesNotDuplicatePacketsAndTimersReleaseState() {
        try (Link link = new Link()) {
            link.introduceLoss();
            link.send(40);
            link.drain();
            assertEquals(15, link.sender.nextDelayMillis());
            assertEquals(300, link.receiver.nextDelayMillis());
            link.time.addAndGet(TimeUnit.MILLISECONDS.toNanos(15));
            link.sender.tick(link::queue);
            assertTrue(link.packets.isEmpty());
            assertEquals(-1, link.sender.nextDelayMillis());
            link.time.addAndGet(TimeUnit.MILLISECONDS.toNanos(285));
            link.receiver.tick(link::queue);
            assertEquals(-1, link.receiver.nextDelayMillis());
        }
    }

    @Test
    void largeDatagramsBypassParityAndPreserveBufferIndices() {
        try (Link link = new Link()) {
            link.introduceLoss();
            ByteBuf payload = Unpooled.buffer(2001).writeZero(2001).skipBytes(1);
            try {
                link.sender.write(payload, link::queue);
                ByteBuf packet = link.packets.element();
                assertEquals(2005, packet.readableBytes());
                assertEquals(AdaptiveUdpFec.DATA, packet.getByte(packet.readerIndex()));
                assertEquals(1, payload.readerIndex());
                assertEquals(-1, link.sender.nextDelayMillis());
            } finally {
                payload.release();
            }
        }
    }

    @Test
    void idleCleanupLifecycleReleasesCachedBuffersOnceAndReallocatesOnNextWrite() throws Exception {
        long waitDeadline = System.currentTimeMillis() + 3000L;
        while (AdaptiveUdpFec.activeSessions() > 0 && System.currentTimeMillis() < waitDeadline) {
            Thread.sleep(20L);
        }
        AdaptiveUdpFec.resetTelemetryForTest();
        long baselineTotal = AdaptiveUdpFec.totalSessions();
        try (Link link = new Link()) {
            assertTrue(AdaptiveUdpFec.activeSessions() >= 2);
            assertEquals(baselineTotal + 2, AdaptiveUdpFec.totalSessions());

            link.introduceLoss(TimeUnit.MILLISECONDS.toNanos(20));
            for (int i = 40; i < 44; i++) {
                link.time.addAndGet(TimeUnit.MILLISECONDS.toNanos(20));
                link.send(i);
                if (i == 41) {
                    link.packets.removeFirst().release();
                }
                link.drain();
            }
            assertEquals(1, link.receiver.recoveredData);
            assertEquals(-1, link.sender.nextDelayMillis());
            assertEquals(-1, link.receiver.nextDelayMillis());
            assertTrue(link.sender.cachedBufferCount() > 0);
            assertTrue(link.sender.nextIdleCleanupDelayMillis() > 0);
            assertTrue(AdaptiveUdpFec.peakCachedXorBytes() >= AdaptiveUdpFec.MAX_SHARD_BYTES);

            // Advance > 5s idle: one-shot tick reclaims all cached XOR buffers and resets target; no further wakeups.
            link.time.addAndGet(TimeUnit.SECONDS.toNanos(6));
            assertEquals(0, link.sender.nextIdleCleanupDelayMillis());
            link.sender.tick(link::queue);
            link.receiver.tick(link::queue);
            assertTrue(link.packets.isEmpty());
            assertEquals(0, link.sender.currentTarget());
            assertEquals(0, link.sender.cachedBufferCount());
            assertEquals(0, link.receiver.cachedBufferCount());
            assertEquals(-1, link.sender.nextDelayMillis());
            assertEquals(-1, link.sender.nextIdleCleanupDelayMillis());
            assertEquals(-1, link.receiver.nextIdleCleanupDelayMillis());

            // Subsequent write after idle sends clean DATA cleanly without stale buffers, and re-enables on new loss.
            link.send(100);
            assertEquals(1, link.packets.size());
            assertEquals(AdaptiveUdpFec.DATA, link.packets.element().getUnsignedByte(link.packets.element().readerIndex()));
            link.drain();
            assertTrue(link.delivered.contains(100));

            // Also verify RX receiving DATA after >5s silence still reclaims previously cached RX shard slots.
            ByteBuf orphanShard = Unpooled.buffer().writeByte(AdaptiveUdpFec.SHARD).writeInt(200).writeByte(0)
                    .writeInt(200).writeZero(200 % 11);
            link.receive(orphanShard);
            link.time.addAndGet(TimeUnit.MILLISECONDS.toNanos(350));
            link.receiver.tick(link::queue);
            assertEquals(1, link.receiver.cachedBufferCount());
            link.time.addAndGet(TimeUnit.SECONDS.toNanos(6));
            link.send(201);
            link.drain();
            assertEquals(0, link.receiver.cachedBufferCount());
        }
        long drainDeadline = System.currentTimeMillis() + 3000L;
        while (AdaptiveUdpFec.activeSessions() > 0 && System.currentTimeMillis() < drainDeadline) {
            Thread.sleep(20L);
        }
        assertEquals(0, AdaptiveUdpFec.activeSessions());
        assertEquals(0, AdaptiveUdpFec.cachedXorBytes());
    }

    @Test
    void weakPartialReportConservativeSamplingHandlesLostDuplicateReorderedAndWrapAround() {
        AtomicLong clock = new AtomicLong(TimeUnit.SECONDS.toNanos(1));
        ArrayDeque<ByteBuf> wire = new ArrayDeque<>();
        ByteBuf payload = Unpooled.buffer(24).writeZero(24);
        try (AdaptiveUdpFec tx = new AdaptiveUdpFec(UnpooledByteBufAllocator.DEFAULT, clock::get, true, 44);
             AdaptiveUdpFec rx = new AdaptiveUdpFec(UnpooledByteBufAllocator.DEFAULT, clock::get, true, 44)) {
            // 1. First partial report with 5 ready samples (seq 0..12, loss at seq 2) activates target=16.
            ArrayDeque<ByteBuf> reports = new ArrayDeque<>();
            for (int seq = 0; seq <= 12; seq++) {
                clock.addAndGet(seq == 12 ? TimeUnit.SECONDS.toNanos(3) : TimeUnit.MILLISECONDS.toNanos(20));
                tx.write(payload, wire::add);
                ByteBuf f = wire.remove();
                if (seq == 2) {
                    f.release();
                } else {
                    try { rx.read(f, ByteBuf::release, reports::add); } finally { f.release(); }
                }
            }
            assertEquals(1, reports.size());
            ByteBuf r1 = reports.remove();
            try { tx.read(r1, ByteBuf::release, ByteBuf::release); } finally { r1.release(); }
            assertEquals(16, tx.currentTarget());

            // 2. Duplicate and reordered older reports are ignored.
            ByteBuf dup = Unpooled.buffer(8).writeByte(AdaptiveUdpFec.REPORT).writeInt(12).writeByte(0).writeByte(0).writeByte(0);
            ByteBuf older = Unpooled.buffer(8).writeByte(AdaptiveUdpFec.REPORT).writeInt(10).writeByte(0).writeByte(0).writeByte(0);
            try {
                tx.read(dup, ByteBuf::release, ByteBuf::release);
                tx.read(older, ByteBuf::release, ByteBuf::release);
            } finally {
                dup.release();
                older.release();
            }
            assertEquals(16, tx.currentTarget());

            // 3a. Consecutive 6-sample partial reports every 3.1s (< 5s REPORT_TIMEOUT): 10 reports = 60 samples < 96,
            // so FEC stays active (would have disabled after 3 reports if each partial report credited 32!).
            int currentSeq = 12;
            for (int k = 0; k < 10; k++) {
                for (int n = 0; n < 6; n++) {
                    clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(500));
                    tx.write(payload, ByteBuf::release);
                }
                currentSeq += 6;
                ByteBuf partialClean = Unpooled.buffer(8).writeByte(AdaptiveUdpFec.REPORT)
                        .writeInt(currentSeq).writeByte(0).writeByte(0).writeByte(0);
                try {
                    tx.read(partialClean, ByteBuf::release, ByteBuf::release);
                } finally {
                    partialClean.release();
                }
                assertEquals(16, tx.currentTarget(), "60 partial clean samples (<96) must not disable FEC");
            }

            // 3b. Now cleanSampled is 60. Simulate a 32-packet lossy burst whose REPORT was LOST on the reverse path,
            // followed within 3.2s (< 5s REPORT_TIMEOUT) by a 6-packet clean partial report (delta = 38 > 32):
            // must NOT add +32 to 60 (=92) or +38 (=98) across the unconfirmed gap; must reset contiguous count to 4!
            for (int n = 0; n < 38; n++) {
                clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(80));
                tx.write(payload, ByteBuf::release);
            }
            currentSeq += 38;
            ByteBuf gapClean = Unpooled.buffer(8).writeByte(AdaptiveUdpFec.REPORT)
                    .writeInt(currentSeq).writeByte(0).writeByte(0).writeByte(0);
            try {
                tx.read(gapClean, ByteBuf::release, ByteBuf::release);
            } finally {
                gapClean.release();
            }
            assertEquals(16, tx.currentTarget(), "lost intermediate report must reset contiguous cleanSampled");

            // One more 32-packet clean burst (4 + 32 = 36 < 96) still keeps FEC active, proving cleanSampled was reset!
            clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(300));
            for (int n = 0; n < 32; n++) tx.write(payload, ByteBuf::release);
            currentSeq += 32;
            ByteBuf burst1 = Unpooled.buffer(8).writeByte(AdaptiveUdpFec.REPORT)
                    .writeInt(currentSeq).writeByte(0).writeByte(0).writeByte(0);
            try {
                tx.read(burst1, ByteBuf::release, ByteBuf::release);
            } finally {
                burst1.release();
            }
            assertEquals(16, tx.currentTarget(), "36 contiguous clean samples after gap reset must keep FEC active");

            // 4. Two more full 32-packet clean burst windows (36 + 32 + 32 = 100 >= 96) disable FEC cleanly.
            for (int w = 0; w < 2; w++) {
                clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(300));
                for (int n = 0; n < 32; n++) tx.write(payload, ByteBuf::release);
                currentSeq += 32;
                ByteBuf burstClean = Unpooled.buffer(8).writeByte(AdaptiveUdpFec.REPORT)
                        .writeInt(currentSeq).writeByte(0).writeByte(0).writeByte(0);
                try {
                    tx.read(burstClean, ByteBuf::release, ByteBuf::release);
                } finally {
                    burstClean.release();
                }
            }
            assertEquals(0, tx.currentTarget(), "three full clean burst windows after gap reset must disable FEC");
        } finally {
            payload.release();
            while (!wire.isEmpty()) wire.remove().release();
        }

        // 5. Verify sequence wrap-around across Integer.MAX_VALUE -> Integer.MIN_VALUE on receiver report generation.
        try (AdaptiveUdpFec rxWrap = new AdaptiveUdpFec(UnpooledByteBufAllocator.DEFAULT, clock::get, true, 44)) {
            ArrayDeque<ByteBuf> wrapReports = new ArrayDeque<>();
            int start = Integer.MAX_VALUE - 10;
            for (int i = 0; i < 42; i++) {
                clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(10));
                int seq = start + i;
                if (i == 5) continue; // 1 isolated missing packet across wrap boundary
                ByteBuf frame = Unpooled.buffer().writeByte(AdaptiveUdpFec.DATA).writeInt(seq).writeInt(i);
                try {
                    rxWrap.read(frame, ByteBuf::release, wrapReports::add);
                } finally {
                    frame.release();
                }
            }
            assertEquals(1, wrapReports.size());
            ByteBuf rep = wrapReports.remove();
            try {
                int pos = rep.readerIndex();
                assertEquals(AdaptiveUdpFec.REPORT, rep.getUnsignedByte(pos));
                assertEquals(1, rep.getUnsignedByte(pos + 5)); // missing
                assertEquals(1, rep.getUnsignedByte(pos + 6)); // isolated
            } finally {
                rep.release();
            }
        }
    }
}
