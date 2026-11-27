package org.rx.net.rss.udp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RssDatagramCipherTest {
    @Test
    void lateAuthenticatedFramesUseTheNegotiatedReorderWindow() {
        for (int flags : new int[]{RssDatagramHandler.AES, RssDatagramHandler.AES | RssDatagramHandler.FEC}) {
            byte[] psk = new byte[32];
            UUID token = UUID.randomUUID(), salt = UUID.randomUUID();
            RssDatagramCipher tx = new RssDatagramCipher(psk, token, salt, false, flags);
            RssDatagramCipher rx = new RssDatagramCipher(psk, token, salt, true, flags);
            ByteBuf plain = Unpooled.buffer().writeZero(RssDatagramHandler.HEADER_BYTES).writeByte(73);
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
                if ((flags & RssDatagramHandler.FEC) != 0) {
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
        RssDatagramCipher client = new RssDatagramCipher(psk, token, salt, false, 7);
        RssDatagramCipher server = new RssDatagramCipher(psk, token, salt, true, 7);
        RssDatagramCipher other = new RssDatagramCipher(psk, token, UUID.randomUUID(), true, 7);
        ByteBuf plain = Unpooled.buffer().writeZero(RssDatagramHandler.HEADER_BYTES).writeInt(73);
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
