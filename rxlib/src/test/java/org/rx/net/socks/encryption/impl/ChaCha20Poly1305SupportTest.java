package org.rx.net.socks.encryption.impl;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.rx.codec.CodecUtil;
import org.rx.io.Bytes;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ChaCha20Poly1305SupportTest {
    @Test
    void encrypt_matchesStandardAeadVectorWithEmptyAad() throws Exception {
        byte[] key = new byte[32];
        byte[] nonce = new byte[12];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) i;
        }
        for (int i = 0; i < nonce.length; i++) {
            nonce[i] = (byte) i;
        }
        ByteBuf input = Unpooled.copiedBuffer("hello chacha20 poly1305", StandardCharsets.UTF_8);
        ByteBuf encrypted = Bytes.directBuffer(64);
        ByteBuf decrypted = Bytes.directBuffer(64);
        try {
            ChaCha20Poly1305Support.encrypt(key, nonce, input, input.readerIndex(), input.readableBytes(), encrypted);
            assertEquals("e19e646c4637c628d6e05792aa2d2e13a61ccbd6624498eb0858564124741979c324ee9acd5a45",
                    CodecUtil.toHex(readBytes(encrypted.slice())));

            ChaCha20Poly1305Support.decrypt(key, nonce, encrypted, encrypted.readerIndex(), encrypted.readableBytes(), decrypted);
            assertArrayEquals("hello chacha20 poly1305".getBytes(StandardCharsets.UTF_8), readBytes(decrypted));
        } finally {
            input.release();
            encrypted.release();
            decrypted.release();
        }
    }

    private static byte[] readBytes(ByteBuf buf) {
        byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        return bytes;
    }
}
