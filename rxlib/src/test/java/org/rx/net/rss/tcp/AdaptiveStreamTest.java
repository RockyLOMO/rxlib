package org.rx.net.rss.tcp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class AdaptiveStreamTest {
    static final class End implements AutoCloseable {
        final ArrayDeque<ByteBuf> tcp = new ArrayDeque<>(), udp = new ArrayDeque<>();
        final List<Integer> delivered = new ArrayList<>();
        final AdaptiveStream stream;

        End(AtomicLong clock) {
            this(clock, 0);
        }

        End(AtomicLong clock, int profile) {
            this(clock, profile, true);
        }

        End(AtomicLong clock, int profile, boolean udpTail) {
            stream = new AdaptiveStream(UnpooledByteBufAllocator.DEFAULT, tcp::add, packet -> {
                assertTrue(packet.readableBytes() <= AdaptiveStream.MAX_UDP_FRAME);
                udp.add(packet);
            }, bytes -> {
                try {
                    delivered.add(bytes.readInt());
                    assertFalse(bytes.isReadable());
                } finally {
                    bytes.release();
                }
            }, clock::get, profile, udpTail);
        }

        void send(int value) {
            ByteBuf b = Unpooled.buffer(4).writeInt(value);
            try {
                stream.write(b);
                assertEquals(0, b.readerIndex());
            } finally {
                b.release();
            }
        }

        void passUdp(End peer) {
            while (!udp.isEmpty()) {
                ByteBuf b = udp.remove();
                try {
                    peer.stream.udp(b);
                } finally {
                    b.release();
                }
            }
        }

        void passTcp(End peer) {
            while (!tcp.isEmpty()) {
                ByteBuf b = tcp.remove();
                try {
                    peer.stream.tcp(b);
                } finally {
                    b.release();
                }
            }
        }

        @Override public void close() {
            stream.close();
            while (!tcp.isEmpty()) tcp.remove().release();
            while (!udp.isEmpty()) udp.remove().release();
        }
    }

    static void probe(End a, End b) {
        a.stream.tick();
        b.stream.tick();
        a.passUdp(b);
        b.passUdp(a);
        a.passUdp(b);
        assertTrue(a.stream.usingUdp());
        assertTrue(b.stream.usingUdp());
    }

    static void ordered(End dest, int count) {
        assertEquals(count, dest.delivered.size());
        for (int i = 0; i < count; i++) {
            assertEquals(i, dest.delivered.get(i).intValue());
        }
    }

    @Test
    void weakProbesIsolatedTailOnceWithoutDroppingHealthyUdp() {
        for (boolean udp : new boolean[]{true, false}) {
            for (boolean ackLost : new boolean[]{true, false}) {
                AtomicLong now = new AtomicLong();
                try (End a = new End(now, AdaptiveStream.WEAK, udp); End b = new End(now, AdaptiveStream.WEAK)) {
                    a.stream.tick();
                    a.passUdp(b);
                    now.addAndGet(TimeUnit.MILLISECONDS.toNanos(150));
                    b.passUdp(a);
                    a.send(0);
                    ByteBuf original = a.udp.remove();
                    try {
                        if (ackLost) b.stream.udp(original);
                        now.addAndGet(TimeUnit.MILLISECONDS.toNanos(200));
                        b.stream.tick();
                        while (!b.tcp.isEmpty()) b.tcp.remove().release();
                        a.stream.tick();
                        assertEquals(1, udp ? a.udp.size() : a.tcp.size());
                        a.stream.tick();
                        assertEquals(1, udp ? a.udp.size() : a.tcp.size());
                        a.passTcp(b);
                        a.passUdp(b);
                        b.stream.udp(original);
                        now.addAndGet(TimeUnit.MILLISECONDS.toNanos(15));
                        b.stream.tick();
                        b.passTcp(a);
                        now.addAndGet(TimeUnit.SECONDS.toNanos(1));
                        a.stream.tick();
                        assertTrue(a.stream.usingUdp());
                        assertTrue(a.tcp.isEmpty());
                        ordered(b, 1);
                        assertEquals(udp ? 1 : 0, a.stream.udpTailProbes);
                        assertEquals(udp ? 1 : 0, a.stream.probesAckedBeforeTcp);
                        assertEquals(0, a.stream.probesHandedToTcp);
                        assertTrue(b.stream.filteredDuplicates >= 1);
                    } finally { original.release(); }
                }
            }
        }
    }

    @Test
    void lostUdpTailProbeHandsOffOnceBeforeFin() {
        AtomicLong now = new AtomicLong();
        try (End a = new End(now, AdaptiveStream.WEAK); End b = new End(now, AdaptiveStream.WEAK)) {
            a.stream.tick();
            a.passUdp(b);
            now.addAndGet(TimeUnit.MILLISECONDS.toNanos(150));
            b.passUdp(a);
            a.send(0);
            a.udp.remove().release();
            now.addAndGet(TimeUnit.MILLISECONDS.toNanos(200));
            a.stream.tick();
            a.udp.remove().release();
            now.addAndGet(TimeUnit.SECONDS.toNanos(1));
            a.stream.tick();
            a.stream.drainToTcp();
            assertEquals(1, a.tcp.size());
            assertEquals(1, a.stream.probesHandedToTcp);
            assertEquals(0, a.stream.probesAckedBeforeTcp);
            a.passTcp(b);
            ordered(b, 1);
        }
    }

    @Test
    void cumulativeAckAndTailRepairCrossTheSignedSequenceBoundary() throws Exception {
        AtomicLong now = new AtomicLong();
        try (End a = new End(now, AdaptiveStream.WEAK); End b = new End(now, AdaptiveStream.WEAK)) {
            java.lang.reflect.Field tx = AdaptiveStream.class.getDeclaredField("nextTx");
            java.lang.reflect.Field rx = AdaptiveStream.class.getDeclaredField("nextRx");
            tx.setAccessible(true);
            rx.setAccessible(true);
            tx.setInt(a.stream, Integer.MAX_VALUE);
            rx.setInt(b.stream, Integer.MAX_VALUE);
            a.stream.tick();
            a.passUdp(b);
            now.addAndGet(TimeUnit.MILLISECONDS.toNanos(150));
            b.passUdp(a);
            a.send(0);
            a.passUdp(b);
            now.addAndGet(TimeUnit.MILLISECONDS.toNanos(160));
            b.stream.tick();
            b.passTcp(a);
            a.send(1);
            a.udp.remove().release();
            now.addAndGet(TimeUnit.MILLISECONDS.toNanos(210));
            a.stream.tick();
            a.passUdp(b);
            now.addAndGet(TimeUnit.MILLISECONDS.toNanos(15));
            b.stream.tick();
            b.passTcp(a);
            a.stream.drainToTcp();
            assertTrue(a.tcp.isEmpty());
            ordered(b, 2);
        }
    }

    @Test
    void onlyUnambiguousFallbackFramesSuppressAckAfterPeerProbe() {
        AtomicLong clock = new AtomicLong(), delivered = new AtomicLong();
        ArrayDeque<ByteBuf> replies = new ArrayDeque<>();
        try (AdaptiveStream receiver = new AdaptiveStream(UnpooledByteBufAllocator.DEFAULT, replies::add, ByteBuf::release,
                b -> { delivered.addAndGet(b.readableBytes()); b.release(); }, clock::get, AdaptiveStream.WEAK)) {
            ByteBuf ping = Unpooled.buffer().writeByte(AdaptiveStream.PING).writeInt(1);
            try { receiver.udp(ping); } finally { ping.release(); }
            int small = AdaptiveStream.MAX_UDP_FRAME - org.rx.net.rss.udp.UdpDailyFec.MAX_OVERHEAD - 5;
            int seq = 0;
            for (; seq < 64; seq++) {
                ByteBuf tcp = Unpooled.buffer().writeByte(AdaptiveStream.DATA).writeInt(seq).writeZero(small + 1);
                try { receiver.tcp(tcp); } finally { tcp.release(); }
            }
            clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(20));
            receiver.tick();
            assertTrue(replies.isEmpty(), "ordinary TCP cannot be an enqueued UDP repair at this size");
            ByteBuf possibleRepair = Unpooled.buffer().writeByte(AdaptiveStream.DATA).writeInt(seq).writeZero(small);
            try { receiver.tcp(possibleRepair); } finally { possibleRepair.release(); }
            clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(20));
            receiver.tick();
            assertEquals(1, replies.size());
            assertEquals(seq + 1, replies.element().getInt(1));
            assertEquals(64L * (small + 1) + small, delivered.get());
        } finally {
            while (!replies.isEmpty()) replies.remove().release();
        }
    }

    @Test
    void parityRecoveryAndTailProbeDeliverOnlyOnceInEitherOrder() {
        for (boolean parityFirst : new boolean[]{true, false}) {
            try (End receiver = new End(new AtomicLong(), AdaptiveStream.WEAK)) {
                // FEC group [0,1]: shard 0 is lost, shard 1 contains stream frame/value 1.
                ByteBuf shard = Unpooled.buffer().writeByte(1).writeInt(1).writeByte(1)
                        .writeByte(AdaptiveStream.DATA).writeInt(1).writeInt(1);
                ByteBuf parity = Unpooled.buffer().writeByte(2).writeInt(0).writeByte(2).writeShort(0)
                        .writeByte(0).writeInt(1).writeInt(1);
                // The probe uses a new FEC serial and the original stream serial/value 0.
                ByteBuf retry = Unpooled.buffer().writeByte(0).writeInt(2)
                        .writeByte(AdaptiveStream.DATA).writeInt(0).writeInt(0);
                try {
                    receiver.stream.udp(shard);
                    receiver.stream.udp(parityFirst ? parity : retry);
                    ordered(receiver, 2);
                    receiver.stream.udp(parityFirst ? retry : parity);
                    ordered(receiver, 2);
                    assertEquals(1, receiver.stream.filteredDuplicates);
                } finally {
                    shard.release();
                    parity.release();
                    retry.release();
                }
            }
        }
    }

    @Test
    void invalidSelectiveRangesCannotReleaseReliableData() {
        AtomicLong clock = new AtomicLong();
        try (End a = new End(clock, AdaptiveStream.WEAK); End b = new End(clock, AdaptiveStream.WEAK)) {
            probe(a, b);
            for (int i = 0; i < 6; i++) a.send(i);
            for (long invalid : new long[]{0L, 1L, 1L << 5}) {
                ByteBuf bad = Unpooled.buffer().writeByte(AdaptiveStream.SACK).writeInt(2).writeLong(invalid);
                try { a.stream.tcp(bad); } finally { bad.release(); }
            }
            a.stream.drainToTcp();
            assertEquals(6, a.tcp.size());
            a.passTcp(b);
            ordered(b, 6);
        }
    }

    @Test
    void weakRepairsTwoHolesWithOneBitmap() {
        AtomicLong clock = new AtomicLong();
        try (End a = new End(clock, AdaptiveStream.WEAK); End b = new End(clock, AdaptiveStream.WEAK)) {
            probe(a, b);
            for (int i = 0; i < 12; i++) {
                a.send(i);
                if (i == 2 || i == 5) a.udp.remove().release();
                a.passUdp(b);
            }
            clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(40));
            b.stream.tick();
            assertEquals(1, b.tcp.size());
            assertEquals(AdaptiveStream.SACK, b.tcp.element().getByte(0));
            b.passTcp(a);
            assertEquals(2, a.tcp.size());
            assertTrue(a.stream.usingUdp());
            // ACKs are outstanding. A FIN must preserve the two reliable repairs already in flight.
            a.stream.drainToTcp();
            assertEquals(2, a.tcp.size());
            a.passTcp(b);
            ordered(b, 12);
        }
    }

    @Test
    void enhancedWindowsGrowOnHighRttAndStopAtTheNegotiatedLimit() {
        for (int profile : new int[]{AdaptiveStream.GAME, AdaptiveStream.WEAK}) {
            AtomicLong clock = new AtomicLong();
            try (End a = new End(clock, profile); End b = new End(clock, profile)) {
                a.stream.tick();
                a.passUdp(b);
                clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(150));
                b.passUdp(a);
                for (int i = 0; i < 256; i++) a.send(i);
                assertTrue(a.stream.usingUdp());
                ByteBuf first = a.udp.remove();
                try {
                    a.passUdp(b);
                    assertTrue(b.delivered.isEmpty());
                    b.stream.udp(first);
                    ordered(b, 256);
                } finally {
                    first.release();
                }
                a.send(256);
                assertFalse(a.stream.usingUdp());
                a.passTcp(b);
                ordered(b, 257);
            }
        }
    }

    @Test
    void repairsOneMissingFrameAndKeepsBothDirectionsActive() {
        AtomicLong clock = new AtomicLong();
        try (End a = new End(clock); End b = new End(clock)) {
            a.send(0);
            assertTrue(a.udp.isEmpty());
            // Delay initial TCP bytes past the first UDP reads.
            probe(a, b);
            for (int i = 1; i < 7; i++) a.send(i);
            ByteBuf delayed = a.udp.remove();
            try {
                a.passUdp(b);
                assertTrue(b.delivered.isEmpty());
                a.passTcp(b);
                clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(50));
                b.stream.tick();
                b.passTcp(a);
                assertEquals(1, a.tcp.size());
                a.passTcp(b);
                b.stream.udp(delayed);
            } finally {
                delayed.release();
            }
            ordered(b, 7);
            assertTrue(a.stream.usingUdp());
            b.send(0);
            b.send(1);
            b.udp.addFirst(b.udp.removeLast());
            b.passUdp(a);
            ordered(a, 2);
        }
    }

    @Test
    void feedbackEnablesFecAndRecoversWithoutReliableReplay() {
        AtomicLong clock = new AtomicLong();
        try (End a = new End(clock); End b = new End(clock)) {
            probe(a, b);
            // Keep four original datagrams in flight beyond the feedback window.
            ArrayDeque<ByteBuf> delayed = new ArrayDeque<>();
            try {
                for (int i = 0; i < 40; i++) {
                    a.send(i);
                    if (i >= 10 && i < 14) delayed.add(a.udp.remove());
                    a.passUdp(b);
                    b.passUdp(a);
                    b.passTcp(a);
                }
                while (!delayed.isEmpty()) {
                    ByteBuf packet = delayed.remove();
                    try { b.stream.udp(packet); } finally { packet.release(); }
                }
                ordered(b, 40);
                for (int i = 40; i < 44; i++) a.send(i);
                assertEquals(5, a.udp.size(), "four data plus one adaptive parity");
                a.udp.remove().release();
                a.passUdp(b);
                ordered(b, 44);
                assertTrue(a.tcp.isEmpty());
            } finally {
                while (!delayed.isEmpty()) delayed.remove().release();
            }
        }
    }

    @Test
    void contiguousGapsRecoverInOrderAndDiscardLateArrivals() {
        for (int burst : new int[]{2, 3, 5}) {
            AtomicLong clock = new AtomicLong();
            try (End a = new End(clock); End b = new End(clock)) {
                probe(a, b);
                ArrayDeque<ByteBuf> delayed = new ArrayDeque<>();
                try {
                    for (int i = 0; i < 15; i++) {
                        a.send(i);
                        if (i >= 2 && i < 2 + burst) delayed.add(a.udp.remove());
                        a.passUdp(b);
                        b.passTcp(a);
                    }
                    for (int i = 0; i < 10 && b.delivered.size() < 15; i++) {
                        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(40));
                        b.stream.tick();
                        b.passTcp(a);
                        a.stream.tick();
                        a.passTcp(b);
                    }
                    a.udp.addAll(delayed);
                    delayed.clear();
                    a.passUdp(b);
                    ordered(b, 15);
                } finally {
                    while (!delayed.isEmpty()) delayed.remove().release();
                }
            }
        }
    }

    @Test
    void missingTailIsRepairedWithoutFollowingDataAndLateCopyIsDiscarded() {
        AtomicLong clock = new AtomicLong();
        try (End a = new End(clock); End b = new End(clock)) {
            a.stream.tick();
            a.passUdp(b);
            clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(30));
            b.passUdp(a);
            assertTrue(a.stream.usingUdp());
            a.send(0);
            long repairAt = a.stream.nextDelayMillis();
            assertTrue(repairAt >= 80 && repairAt <= 150, "tail repair deadline: " + repairAt);
            clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(repairAt - 1));
            a.stream.tick();
            assertTrue(a.tcp.isEmpty());
            clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(1));
            a.stream.tick();
            a.passTcp(b);
            a.passUdp(b);
            ordered(b, 1);
        }
    }

    @Test
    void oneWayFailureDrainsOnceAndCanProbeAgain() {
        AtomicLong clock = new AtomicLong();
        try (End a = new End(clock); End b = new End(clock)) {
            probe(a, b);
            a.send(0);
            a.send(1);
            clock.addAndGet(TimeUnit.SECONDS.toNanos(1));
            a.stream.tick();
            assertFalse(a.stream.usingUdp());
            a.send(2);
            a.passTcp(b);
            a.passUdp(b);
            ordered(b, 3);
            clock.addAndGet(TimeUnit.SECONDS.toNanos(3));
            probe(a, b);
            a.send(3);
            a.passUdp(b);
            ordered(b, 4);
        }
    }

    @Test
    void senderWindowLimitsMemoryAndFinDrainsOutstandingData() {
        AtomicLong clock = new AtomicLong();
        try (End a = new End(clock); End b = new End(clock)) {
            probe(a, b);
            for (int i = 0; i < 100; i++) a.send(i);
            assertFalse(a.stream.usingUdp());
            assertEquals(64, a.udp.size());
            a.passTcp(b);
            a.passUdp(b);
            ordered(b, 100);
            clock.addAndGet(TimeUnit.SECONDS.toNanos(3));
            probe(a, b);
            a.send(100);
            a.stream.drainToTcp();
            a.passTcp(b);
            a.passUdp(b);
            ordered(b, 101);
        }
    }
}
