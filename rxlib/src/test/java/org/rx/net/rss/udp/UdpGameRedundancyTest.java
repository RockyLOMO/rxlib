package org.rx.net.rss.udp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.socket.DatagramPacket;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class UdpGameRedundancyTest {
    private static final InetSocketAddress CLIENT = new InetSocketAddress("127.0.0.1", 15001);
    private static final InetSocketAddress SERVER = new InetSocketAddress("127.0.0.1", 15002);
    private static final InetSocketAddress ALIAS = new InetSocketAddress("127.0.0.1", 15003);

    private static EmbeddedChannel channel(InetSocketAddress addr, DailyUdpWire wire) {
        return new EmbeddedChannel(wire) {
            @Override public SocketAddress localAddress() { return addr; }
        };
    }

    private static void receive(EmbeddedChannel target, DatagramPacket packet, InetSocketAddress from) {
        target.writeInbound(new DatagramPacket(packet.content(), packet.recipient(), from));
    }

    @Test
    void authenticatedCopiesAdaptPerDirectionAndDeliverFirstCopyOnce() {
        String id = UUID.randomUUID().toString();
        byte[] key = new byte[32];
        AtomicLong now = new AtomicLong();
        EmbeddedChannel client = channel(CLIENT, new DailyUdpWire(id, SERVER, ALIAS, null, now::get, DailyUdpWire.GAME, null, key));
        EmbeddedChannel server = channel(SERVER, new DailyUdpWire(id, null, null, a -> false, now::get, DailyUdpWire.GAME, null, key));
        try {
            for (int seq = 0; seq < 535; seq++) {
                now.addAndGet(5_000_000L);
                client.writeOutbound(new DatagramPacket(Unpooled.buffer(4).writeInt(seq), SERVER));
                int copies = 0;
                DatagramPacket packet;
                boolean blocked = (seq < 160 || seq >= 465) && seq % 32 == 8;
                while ((packet = client.readOutbound()) != null) {
                    int index = copies++;
                    if (seq == 0 && index == 0) {
                        ByteBuf forged = packet.content().copy();
                        forged.setByte(forged.writerIndex() - 1, ~forged.getByte(forged.writerIndex() - 1));
                        receive(server, new DatagramPacket(forged, SERVER), CLIENT);
                        assertNull(server.readInbound());
                    }
                    if (blocked || seq < 160 && seq % 32 == 9 && index == 0) {
                        packet.release();
                    } else {
                        if (seq == 80 && index == 1) receive(server, packet.retainedDuplicate(), CLIENT);
                        receive(server, packet, CLIENT);
                    }
                }
                if (seq == 0) assertEquals(1, copies);
                if (seq == 150) assertEquals(3, copies);
                if (seq == 459) assertEquals(1, copies);
                if (seq == 534) assertEquals(2, copies, "loss is observable after returning to 1x");
                DatagramPacket payload = server.readInbound();
                if (blocked || copies == 1 && seq < 160 && seq % 32 == 9) {
                    assertNull(payload);
                } else {
                    assertNotNull(payload);
                    assertEquals(seq, payload.content().readInt());
                    payload.release();
                }
                assertNull(server.readInbound());
                while ((packet = server.readOutbound()) != null) receive(client, packet, SERVER);
                assertNull(client.readInbound());
            }
            server.writeOutbound(new DatagramPacket(Unpooled.buffer(1).writeByte(42), CLIENT));
            DatagramPacket first = server.readOutbound(), second = server.readOutbound();
            assertNotNull(first);
            assertNull(second, "return direction starts independently at one copy");
            receive(client, first, SERVER);
            DatagramPacket payload = client.readInbound();
            assertEquals(42, payload.content().readUnsignedByte());
            payload.release();
            assertNull(client.readInbound());
        } finally {
            client.finishAndReleaseAll();
            server.finishAndReleaseAll();
        }
    }

    @Test
    void sustainedFeedbackDelayDisablesExtraCopiesBeforeCongestionCanPromoteThem() {
        UdpGameRedundancy state = new UdpGameRedundancy(new AtomicLong());
        for (int i = 0; i < 104; i++) {
            long sendTime = i * 10_000_000L;
            state.begin(sendTime);
            state.extra(i, 100, true, sendTime);
            if (i == 39 || i == 71 || i == 103) {
                ByteBuf report = Unpooled.buffer(8).writeByte(UdpGameRedundancy.REPORT).writeInt(i - 8)
                        .writeByte(31).writeByte(60).writeByte(30);
                try {
                    long delay = i == 39 ? 30_000_000L : 100_000_000L;
                    state.read(report, report.alloc(), sendTime + delay, true, b -> b.release(), b -> b.release());
                } finally {
                    report.release();
                }
            }
        }
        assertEquals(1, state.copies);
    }

    @Test
    void missingFeedbackAndLocalPressureBoundExtraTraffic() {
        UdpGameRedundancy game = new UdpGameRedundancy(new AtomicLong());
        for (int i = 0; i < 64; i++) {
            int seq = game.begin(0);
            assertEquals(1, game.copies);
            assertFalse(game.extra(seq, 100, false, 0));
            if (i < 8) assertTrue(game.extra(seq, 1024, true, 0));
            else assertFalse(game.extra(seq, 1024, true, 0));
        }
        game.begin(0);
        assertEquals(1, game.copies);
        assertEquals(65, game.sentUnique);
        assertEquals(73, game.sentCopies);
        assertTrue(game.extra(64, 100, true, TimeUnit.MILLISECONDS.toNanos(2)));
        assertTrue(game.suppressed > 0);
    }

    @Test
    void delayedFeedbackCanGrowHistoryAndCountsExpiredWindows() {
        UdpGameRedundancy state = new UdpGameRedundancy(new AtomicLong());
        try {
            for (int i = 0; i < 1000; i++) {
                state.begin(i * 1_000_000L);
                if (i != 400 && i != 800 && i != 840) continue;
                ByteBuf report = Unpooled.buffer(8).writeByte(UdpGameRedundancy.REPORT).writeInt(i - 310)
                        .writeByte(30).writeByte(30).writeByte(30);
                try {
                    state.read(report, report.alloc(), i * 1_000_000L, true, ByteBuf::release, ByteBuf::release);
                } finally { report.release(); }
            }
            assertEquals(1, state.expiredReports);
            assertEquals(2, state.copies);
        } finally { state.close(); }
    }

    @Test
    void negotiatedProtectionRequiresIndependentSessionMaterial() {
        String id = UUID.randomUUID().toString();
        assertThrows(IllegalArgumentException.class, () -> DailyUdpWire.server(id, a -> false, DailyUdpWire.GAME, null));
        assertThrows(IllegalArgumentException.class, () -> DailyUdpWire.server(id, a -> false, DailyUdpWire.FEC | DailyUdpWire.WEAK, null));
        assertThrows(IllegalArgumentException.class, () -> DailyUdpWire.server(id, a -> false, DailyUdpWire.WEAK, null, new byte[32]));
        assertThrows(IllegalArgumentException.class, () -> DailyUdpWire.server(id, a -> false, DailyUdpWire.GAME | DailyUdpWire.WEAK | DailyUdpWire.FEC, null, new byte[32]));
    }
}
