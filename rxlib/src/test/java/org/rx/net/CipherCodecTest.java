package org.rx.net;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import org.rx.codec.XChaCha20Poly1305Util;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class CipherCodecTest {
    @Test
    void aesCipher_roundTripsThroughNettyPipeline() {
        assertRoundTrip((short) 1, "aes codec payload");
    }

    @Test
    void xChaChaCipher_roundTripsThroughNettyPipeline() {
        assertRoundTrip((short) 2, "xchacha codec payload");
    }

    private static void assertRoundTrip(short cipher, String payload) {
        SocketConfig config = new SocketConfig();
        config.setCipher(cipher);
        config.setCipherKey(XChaCha20Poly1305Util.generateKey());
        EmbeddedChannel encoder = new EmbeddedChannel(CipherEncoder.DEFAULT);
        EmbeddedChannel decoder = new EmbeddedChannel(new CipherDecoder());
        encoder.attr(SocketConfig.ATTR_CONF).set(config);
        decoder.attr(SocketConfig.ATTR_CONF).set(config);
        ByteBuf frame = null;
        ByteBuf decoded = null;
        try {
            encoder.writeOutbound(Unpooled.copiedBuffer(payload, StandardCharsets.UTF_8));
            frame = encoder.readOutbound();
            assertNotNull(frame);

            decoder.writeInbound(frame);
            frame = null;
            decoded = decoder.readInbound();
            assertNotNull(decoded);
            assertEquals(payload, decoded.toString(StandardCharsets.UTF_8));
        } finally {
            release(frame);
            release(decoded);
            encoder.finishAndReleaseAll();
            decoder.finishAndReleaseAll();
        }
    }

    private static void release(ByteBuf buf) {
        if (buf != null && buf.refCnt() > 0) {
            buf.release();
        }
    }
}
