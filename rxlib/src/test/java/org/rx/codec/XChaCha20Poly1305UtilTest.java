package org.rx.codec;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class XChaCha20Poly1305UtilTest {
    @Test
    void decrypt_acceptsPyCryptodomeVector() {
        byte[] key = CodecUtil.fromHex("000102030405060708090a0b0c0d0e0f"
                + "101112131415161718191a1b1c1d1e1f");
        byte[] packet = CodecUtil.fromHex("404142434445464748494a4b4c4d4e4f5051525354555657"
                + "8c7a6d1193881824bfd9d7d1c3e554a1a28f8da0762d36e80758932c7a704af3368d671d21"
                + "b2e9758cc1952b6ffaad98ef035d8500f7d1bcc294699a33dd22317a49941d77d86b"
                + "11df15e69c7a0becaf11ffd369d22491");

        byte[] plaintext = XChaCha20Poly1305Util.decrypt(key, packet);
        assertEquals("XChaCha20-Poly1305 deterministic test message with more than one block.",
                new String(plaintext, StandardCharsets.UTF_8));
    }

    @Test
    void encryptDecrypt_roundTripsBoundaryLengths() {
        byte[] key = XChaCha20Poly1305Util.generateKey();
        int[] lengths = new int[]{0, 1, 15, 16, 17, 63, 64, 65, 1024, 4097};
        for (int length : lengths) {
            byte[] plaintext = payload(length);
            byte[] encrypted = XChaCha20Poly1305Util.encrypt(key, plaintext);
            assertEquals(24 + length + 16, encrypted.length, "length=" + length);
            assertArrayEquals(plaintext, XChaCha20Poly1305Util.decrypt(key, encrypted), "length=" + length);
        }
    }

    @Test
    void decrypt_rejectsTamperedTagAndShortInput() {
        byte[] key = XChaCha20Poly1305Util.generateKey();
        byte[] encrypted = XChaCha20Poly1305Util.encrypt(key, "tamper".getBytes(StandardCharsets.UTF_8));
        encrypted[encrypted.length - 1] ^= 1;

        assertThrows(SecurityException.class, () -> XChaCha20Poly1305Util.decrypt(key, encrypted));
        assertThrows(IllegalArgumentException.class, () -> XChaCha20Poly1305Util.decrypt(key, new byte[39]));
    }

    private static byte[] payload(int len) {
        byte[] payload = new byte[len];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) i;
        }
        return payload;
    }
}
