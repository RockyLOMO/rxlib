package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class Udp2rawAuthenticatorTest {
    @Test
    void firstPacketMacSignsFrameAndPayload() {
        byte[] secret = new byte[32];
        for (int i = 0; i < secret.length; i++) {
            secret[i] = (byte) (i + 1);
        }
        ByteBuf payload = Unpooled.copiedBuffer("auth-payload", StandardCharsets.UTF_8);
        ByteBuf authTag = null;
        try {
            Udp2rawFrame frame = Udp2rawFrame.data(1L, 2L, 100L, 1L);
            frame.setFlags(Udp2rawCodec.FLAG_NEW_CONN | Udp2rawCodec.FLAG_HAS_CLIENT
                    | Udp2rawCodec.FLAG_HAS_DST | Udp2rawCodec.FLAG_AUTH_TAG);
            frame.setClientSource(new InetSocketAddress("127.0.0.1", 30001));
            frame.setDestination(org.rx.net.Sockets.newUnresolvedEndpoint("127.0.0.1", 53));
            authTag = Udp2rawAuthenticator.sign(UnpooledByteBufAllocator.DEFAULT, secret, frame, payload);
            frame.setAuthTag(authTag);

            assertTrue(Udp2rawAuthenticator.requiresAuth(Udp2rawAuthMode.FIRST_PACKET_MAC, true, frame.getFlags()));
            assertTrue(Udp2rawAuthenticator.verify(secret, frame, payload));
            payload.setByte(payload.readerIndex(), 'A');
            assertFalse(Udp2rawAuthenticator.verify(secret, frame, payload));
        } finally {
            if (authTag != null) {
                authTag.release();
            }
            payload.release();
        }
    }

    @Test
    void seqWindowDropsInWindowDuplicate() {
        Udp2rawSeqWindow window = new Udp2rawSeqWindow();
        assertTrue(window.checkAndMark(10));
        assertTrue(window.checkAndMark(11));
        assertFalse(window.checkAndMark(10));
        assertTrue(window.checkAndMark(9));
        assertFalse(window.checkAndMark(9));
    }

    @Test
    void sessionKeySeparatesConnId() {
        Udp2rawSessionKey a = new Udp2rawSessionKey(1L, 2L, 3L);
        Udp2rawSessionKey b = new Udp2rawSessionKey(1L, 2L, 4L);
        assertNotEquals(a, b);
        assertEquals(a, new Udp2rawSessionKey(1L, 2L, 3L));
    }
}
