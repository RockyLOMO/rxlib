package org.rx.net.udp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.socket.DatagramPacket;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.UUID;
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
            for (int seq = 0; seq < 435; seq++) {
                now.addAndGet(5_000_000L);
                client.writeOutbound(new DatagramPacket(Unpooled.buffer(4).writeInt(seq), SERVER));
                int copies = 0;
                DatagramPacket packet;
                boolean blocked = (seq < 125 || seq >= 365) && seq % 32 == 8;
                while ((packet = client.readOutbound()) != null) {
                    int index = copies++;
                    if (seq == 0 && index == 0) {
                        ByteBuf forged = packet.content().copy();
                        forged.setByte(forged.writerIndex() - 1, ~forged.getByte(forged.writerIndex() - 1));
                        receive(server, new DatagramPacket(forged, SERVER), CLIENT);
                        assertNull(server.readInbound());
                    }
                    if (blocked || seq < 125 && seq % 32 == 9 && index == 0) {
                        packet.release();
                    } else {
                        if (seq == 0 && index == 1) receive(server, packet.retainedDuplicate(), CLIENT);
                        receive(server, packet, CLIENT);
                    }
                }
                if (seq == 0) assertEquals(2, copies);
                if (seq == 100) assertEquals(3, copies);
                if (seq == 359) assertEquals(1, copies);
                if (seq == 434) assertEquals(2, copies, "loss is observable after returning to 1x");
                DatagramPacket payload = server.readInbound();
                if (blocked) {
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
            assertNotNull(second);
            receive(client, second, SERVER);
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
    void missingFeedbackAndLocalPressureBoundExtraTraffic() {
        UdpGameRedundancy game = new UdpGameRedundancy();
        for (int i = 0; i < 64; i++) {
            int seq = game.begin(0);
            assertEquals(2, game.copies);
            assertFalse(game.extra(seq, 100, false, 0));
            if (i < 8) assertTrue(game.extra(seq, 65536, true, 0));
            else assertFalse(game.extra(seq, 65536, true, 0));
        }
        game.begin(0);
        assertEquals(1, game.copies);
        assertEquals(65, game.sentUnique);
        assertEquals(73, game.sentCopies);
        assertTrue(game.suppressed > 0);
    }

    @Test
    void negotiatedProtectionRequiresIndependentSessionMaterial() {
        String id = UUID.randomUUID().toString();
        assertThrows(IllegalArgumentException.class, () -> DailyUdpWire.server(id, a -> false, DailyUdpWire.GAME, null));
        assertThrows(IllegalArgumentException.class, () -> DailyUdpWire.server(id, a -> false, DailyUdpWire.FEC | DailyUdpWire.WEAK, null));
        assertThrows(IllegalArgumentException.class, () -> DailyUdpWire.server(id, a -> false, DailyUdpWire.WEAK, null, new byte[32]));
        assertThrows(IllegalArgumentException.class, () -> DailyUdpWire.server(id, a -> false, DailyUdpWire.GAME | DailyUdpWire.FEC, null, new byte[32]));
    }
}
