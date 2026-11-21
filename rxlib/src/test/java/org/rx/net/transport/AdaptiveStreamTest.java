package org.rx.net.transport;

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
            }, clock::get);
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
