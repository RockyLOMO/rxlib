package org.rx.net.rss.udp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class UdpDailyFecTest {
    static class Link implements AutoCloseable {
        final AtomicLong time = new AtomicLong();
        final UdpDailyFec sender = new UdpDailyFec(UnpooledByteBufAllocator.DEFAULT, time::get);
        final UdpDailyFec receiver = new UdpDailyFec(UnpooledByteBufAllocator.DEFAULT, time::get);
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
            assertEquals(UdpDailyFec.WIRE_HEADROOM, frame.readerIndex());
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
                        assertEquals(UdpDailyFec.WIRE_HEADROOM, report.readerIndex());
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
                assertEquals(UdpDailyFec.DATA, wire.getByte(wire.readerIndex()));
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
        try (UdpDailyFec fec = new UdpDailyFec(UnpooledByteBufAllocator.DEFAULT, clock::get)) {
            AtomicLong deliveries = new AtomicLong();
            for (int index = 0; index < 3; index++) {
                ByteBuf wire = Unpooled.buffer();
                if (index < 2) wire.writeByte(UdpDailyFec.SHARD).writeInt(index).writeByte(index).writeByte(7);
                else wire.writeByte(UdpDailyFec.PARITY).writeInt(0).writeByte(2).writeShort(0).writeByte(0);
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
        try (UdpDailyFec tx = new UdpDailyFec(UnpooledByteBufAllocator.DEFAULT, clock::get);
             UdpDailyFec rx = new UdpDailyFec(UnpooledByteBufAllocator.DEFAULT, clock::get)) {
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
                            if (f.getUnsignedByte(f.readerIndex()) == UdpDailyFec.SHARD && i == 5) {
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
    void weakChargesVariableParityAtItsEncodedByteCostAndCanCancelTail() {
        AtomicLong time = new AtomicLong();
        final int authHeader = 44;
        long[] wireBytes = new long[2];
        java.util.function.Consumer<ByteBuf> count = frame -> {
            try {
                int kind = frame.getUnsignedByte(frame.readerIndex()) == UdpDailyFec.PARITY ? 1 : 0;
                wireBytes[kind] += frame.readableBytes() + authHeader;
            } finally {
                frame.release();
            }
        };
        try (UdpDailyFec fec = new UdpDailyFec(UnpooledByteBufAllocator.DEFAULT, time::get, true, authHeader)) {
            ByteBuf small = Unpooled.buffer(10).writeZero(10), large = Unpooled.buffer(1400).writeZero(1400);
            try {
                for (int i = 0; i < 40; i++) fec.write(small, count);
                assertEquals(0, fec.paritySent);
                ByteBuf report = Unpooled.buffer(8).writeByte(UdpDailyFec.REPORT).writeInt(39)
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
                ByteBuf shard = Unpooled.buffer().writeByte(UdpDailyFec.SHARD).writeInt(i * 2)
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
                    link.receive(Unpooled.buffer().writeByte(UdpDailyFec.SHARD).writeInt(base)
                            .writeByte(0).writeInt(0));
                    link.time.addAndGet(TimeUnit.MILLISECONDS.toNanos(elapsed));
                    for (int i = 2; i <= age + 1; i++) {
                        link.receive(Unpooled.buffer().writeByte(UdpDailyFec.DATA).writeInt(base + i)
                                .writeInt(0));
                    }
                    for (int i = 0; i < 2; i++) {
                        link.receive(Unpooled.buffer().writeByte(UdpDailyFec.PARITY).writeInt(base)
                                .writeByte(2).writeShort(0).writeInt(0));
                    }
                    assertEquals(elapsed < 300 ? 1 : 0, link.receiver.recoveredData);
                    link.receive(Unpooled.buffer().writeByte(UdpDailyFec.SHARD).writeInt(base + 1)
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
                    if (frame.getUnsignedByte(pos) == UdpDailyFec.PARITY) {
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
            assertEquals(UdpDailyFec.PARITY, parity.getByte(parity.readerIndex()));
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
            assertEquals(UdpDailyFec.DATA, link.packets.element().getByte(UdpDailyFec.WIRE_HEADROOM));
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
                assertEquals(UdpDailyFec.DATA, packet.getByte(packet.readerIndex()));
                assertEquals(1, payload.readerIndex());
                assertEquals(-1, link.sender.nextDelayMillis());
            } finally {
                payload.release();
            }
        }
    }
}
