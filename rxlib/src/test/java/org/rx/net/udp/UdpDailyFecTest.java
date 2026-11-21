package org.rx.net.udp;

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
            for (int i = 0; i < 40; i++) {
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
