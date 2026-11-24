package org.rx.net.rss.udp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DailyUdpCipherTest {
    @Test
    void lateAuthenticatedFramesUseTheNegotiatedReorderWindow() {
        for (int flags : new int[]{DailyUdpWire.AES, DailyUdpWire.AES | DailyUdpWire.FEC}) {
            byte[] psk = new byte[32];
            UUID token = UUID.randomUUID(), salt = UUID.randomUUID();
            DailyUdpCipher tx = new DailyUdpCipher(psk, token, salt, false, flags);
            DailyUdpCipher rx = new DailyUdpCipher(psk, token, salt, true, flags);
            ByteBuf plain = Unpooled.buffer().writeZero(DailyUdpWire.HEADER_BYTES).writeByte(73);
            ByteBuf late = tx.seal(plain);
            try {
                for (int i = 0; i < 320; i++) {
                    ByteBuf frame = tx.seal(plain);
                    try {
                        ByteBuf data = rx.open(frame);
                        assertNotNull(data);
                        data.release();
                    } finally {
                        frame.release();
                    }
                }
                if ((flags & DailyUdpWire.FEC) != 0) {
                    ByteBuf data = rx.open(late);
                    assertNotNull(data);
                    data.release();
                }
                assertNull(rx.open(late));
            } finally {
                late.release();
                plain.release();
            }
        }
    }

    @Test
    void authenticatesHeadersAndRejectsReplayReflectionAndCrossSessionPackets() {
        byte[] psk = new byte[32];
        UUID token = UUID.randomUUID(), salt = UUID.randomUUID();
        DailyUdpCipher client = new DailyUdpCipher(psk, token, salt, false, 7);
        DailyUdpCipher server = new DailyUdpCipher(psk, token, salt, true, 7);
        DailyUdpCipher other = new DailyUdpCipher(psk, token, UUID.randomUUID(), true, 7);
        ByteBuf plain = Unpooled.buffer().writeZero(DailyUdpWire.HEADER_BYTES).writeInt(73);
        ByteBuf first = client.seal(plain), second = client.seal(plain);
        try {
            assertNull(client.open(first));
            assertNull(other.open(first));
            first.setByte(16, 1);
            assertNull(server.open(first));
            first.setByte(16, 0);
            for (ByteBuf packet : new ByteBuf[]{second, first}) {
                ByteBuf payload = server.open(packet);
                assertNotNull(payload);
                try {
                    assertEquals(73, payload.readInt());
                } finally {
                    payload.release();
                }
                assertNull(server.open(packet));
            }
        } finally {
            first.release();
            second.release();
            plain.release();
        }
    }
}
