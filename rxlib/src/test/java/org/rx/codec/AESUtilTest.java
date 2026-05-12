package org.rx.codec;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.rx.io.Bytes;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AESUtilTest {
    @Test
    void generateKeyFromString_returnsAes128KeyBytes() {
        byte[] key = AESUtil.generateKey("string-key");
        assertEquals(16, key.length);
        assertArrayEquals(key, AESUtil.generateKey("string-key"));
        assertArrayEquals("payload".getBytes(StandardCharsets.UTF_8),
                AESUtil.decrypt(AESUtil.encrypt("payload".getBytes(StandardCharsets.UTF_8), key), key));
    }

    @Test
    void byteBufOverloads_writeIntoProvidedPooledBuffers() {
        byte[] key = "pooled-aes-key".getBytes(StandardCharsets.UTF_8);
        byte[] payload = "pooled bytebuf aes payload".getBytes(StandardCharsets.UTF_8);
        ByteBuf input = Bytes.directBuffer(payload.length);
        ByteBuf encrypted = Bytes.directBuffer(64);
        ByteBuf decrypted = Bytes.directBuffer(payload.length);
        try {
            input.writeBytes(payload);
            AESUtil.encrypt(input, key, encrypted);
            assertTrue(encrypted.isDirect());
            assertEquals(12 + payload.length + 16, encrypted.readableBytes());
            assertEquals(payload.length, input.readableBytes());

            AESUtil.decrypt(encrypted, key, decrypted);
            assertArrayEquals(payload, readBytes(decrypted));
            assertEquals(0, encrypted.readableBytes());
        } finally {
            input.release();
            encrypted.release();
            decrypted.release();
        }
    }

    @Test
    void decrypt_rejectsTamperedCiphertext() {
        byte[] key = "pooled-aes-key".getBytes(StandardCharsets.UTF_8);
        byte[] payload = "auth payload".getBytes(StandardCharsets.UTF_8);
        ByteBuf input = Bytes.directBuffer(payload.length);
        ByteBuf encrypted = null;
        try {
            input.writeBytes(payload);
            encrypted = AESUtil.encrypt(input, key);
            encrypted.setByte(encrypted.writerIndex() - 1, encrypted.getByte(encrypted.writerIndex() - 1) ^ 1);
            ByteBuf packet = encrypted;
            assertThrows(Exception.class, () -> AESUtil.decrypt(packet, key));
        } finally {
            input.release();
            if (encrypted != null) {
                encrypted.release();
            }
        }
    }

    private static byte[] readBytes(ByteBuf buf) {
        byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        return bytes;
    }
}
