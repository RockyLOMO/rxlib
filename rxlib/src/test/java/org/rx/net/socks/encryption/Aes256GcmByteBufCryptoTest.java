package org.rx.net.socks.encryption;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import org.junit.jupiter.api.Test;
import org.rx.codec.CodecUtil;
import org.rx.net.socks.encryption.impl.Aes128GcmByteBufCrypto;
import org.rx.net.socks.encryption.impl.Aes256GcmByteBufCrypto;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class Aes256GcmByteBufCryptoTest {
    @Test
    void tcpRoundTrip_usesDirectOutputAndConsumesDirectInput() {
        Aes256GcmByteBufCrypto enc = new Aes256GcmByteBufCrypto(CipherKind.AES_256_GCM.getCipherName(), "pwd");
        Aes256GcmByteBufCrypto dec = new Aes256GcmByteBufCrypto(CipherKind.AES_256_GCM.getCipherName(), "pwd");
        enc.setForUdp(false);
        dec.setForUdp(false);
        ByteBuf input = PooledByteBufAllocator.DEFAULT.directBuffer();
        ByteBuf encrypted = null;
        ByteBuf decrypted = null;
        try {
            byte[] payload = payload(17000);
            input.writeBytes(payload);
            encrypted = enc.encrypt(input);
            assertTrue(encrypted.isDirect());
            assertEquals(0, input.readableBytes());

            decrypted = dec.decrypt(encrypted);
            assertArrayEquals(payload, readBytes(decrypted));
        } finally {
            release(input, encrypted, decrypted);
            enc.close();
            dec.close();
        }
    }

    @Test
    void tcpDecrypt_supportsHalfPacketAfterSalt() {
        Aes256GcmByteBufCrypto enc = new Aes256GcmByteBufCrypto(CipherKind.AES_256_GCM.getCipherName(), "pwd");
        Aes256GcmByteBufCrypto dec = new Aes256GcmByteBufCrypto(CipherKind.AES_256_GCM.getCipherName(), "pwd");
        enc.setForUdp(false);
        dec.setForUdp(false);
        ByteBuf input = Unpooled.copiedBuffer("tcp-half-packet", StandardCharsets.UTF_8);
        ByteBuf encrypted = null;
        ByteBuf part1 = null;
        ByteBuf part2 = null;
        ByteBuf out1 = null;
        ByteBuf out2 = null;
        try {
            encrypted = enc.encrypt(input);
            int firstLen = 48; // salt + incomplete encrypted length chunk
            part1 = encrypted.retainedSlice(encrypted.readerIndex(), firstLen);
            part2 = encrypted.retainedSlice(encrypted.readerIndex() + firstLen, encrypted.readableBytes() - firstLen);

            out1 = dec.decrypt(part1);
            assertFalse(out1.isReadable());

            out2 = dec.decrypt(part2);
            assertEquals("tcp-half-packet", out2.toString(StandardCharsets.UTF_8));
        } finally {
            release(input, encrypted, part1, part2, out1, out2);
            enc.close();
            dec.close();
        }
    }

    @Test
    void tcpDecrypt_supportsSaltSplitAcrossReads() {
        ICrypto aesEnc = ICrypto.get(CipherKind.AES_256_GCM.getCipherName(), "pwd");
        ICrypto aesDec = ICrypto.get(CipherKind.AES_256_GCM.getCipherName(), "pwd");
        ICrypto aes128Enc = ICrypto.get(CipherKind.AES_128_GCM.getCipherName(), "pwd");
        ICrypto aes128Dec = ICrypto.get(CipherKind.AES_128_GCM.getCipherName(), "pwd");
        ICrypto chachaEnc = ICrypto.get(CipherKind.CHACHA20_IETF_POLY1305.getCipherName(), "pwd");
        ICrypto chachaDec = ICrypto.get(CipherKind.CHACHA20_IETF_POLY1305.getCipherName(), "pwd");
        assertTcpDecryptSupportsSaltSplit(aesEnc, aesDec, "aes-split-salt", 32);
        assertTcpDecryptSupportsSaltSplit(aes128Enc, aes128Dec, "aes128-split-salt", 16);
        assertTcpDecryptSupportsSaltSplit(chachaEnc, chachaDec, "chacha-split-salt", 32);
    }

    @Test
    void aes256GcmTcp_decryptsDeterministicJceVector() throws Exception {
        String password = "deterministic-password";
        byte[] salt = payload(32);
        byte[] plaintext = "shadowsocks-aead-vector".getBytes(StandardCharsets.UTF_8);
        byte[] packet = aesGcmTcpPacketByJce(password, salt, plaintext, 32);
        assertEquals("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
                        + "eea01a1e33e4a2b10c843ee295810b2baf4c48f9629bf3bff90e1f17c19e2b"
                        + "0348028199d1515e85536d4d865f6f076f89968ec8e07c7908be",
                CodecUtil.toHex(packet));

        ICrypto dec = ICrypto.get(CipherKind.AES_256_GCM.getCipherName(), password);
        ByteBuf out = null;
        try {
            out = dec.decrypt(Unpooled.wrappedBuffer(packet));
            assertArrayEquals(plaintext, readBytes(out));
        } finally {
            release(out);
            close(dec);
        }
    }

    @Test
    void aes128GcmTcp_decryptsDeterministicJceVector() throws Exception {
        String password = "deterministic-password";
        byte[] salt = payload(16);
        byte[] plaintext = "shadowsocks-aead-vector".getBytes(StandardCharsets.UTF_8);
        byte[] packet = aesGcmTcpPacketByJce(password, salt, plaintext, 16);
        ICrypto dec = ICrypto.get(CipherKind.AES_128_GCM.getCipherName(), password);
        ByteBuf out = null;
        try {
            out = dec.decrypt(Unpooled.wrappedBuffer(packet));
            assertArrayEquals(plaintext, readBytes(out));
        } finally {
            release(out);
            close(dec);
        }
    }

    @Test
    void chacha20Poly1305Tcp_decryptsIndependentVector() {
        byte[] plaintext = "shadowsocks-aead-vector".getBytes(StandardCharsets.UTF_8);
        byte[] packet = CodecUtil.fromHex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
                + "d9fcc5cb03ad027634516bef9ebfdc9ef61a91c651b453c832ff7637d6cb900"
                + "fc64ba52efc17a0179f23139f97d898541fa7e96baebf0a4034");
        ICrypto dec = ICrypto.get(CipherKind.CHACHA20_IETF_POLY1305.getCipherName(), "deterministic-password");
        ByteBuf out = null;
        try {
            out = dec.decrypt(Unpooled.wrappedBuffer(packet));
            assertArrayEquals(plaintext, readBytes(out));
        } finally {
            release(out);
            close(dec);
        }
    }

    @Test
    void udpRoundTrip_acceptsCompositeInput() {
        Aes256GcmByteBufCrypto enc = new Aes256GcmByteBufCrypto(CipherKind.AES_256_GCM.getCipherName(), "pwd");
        Aes256GcmByteBufCrypto dec = new Aes256GcmByteBufCrypto(CipherKind.AES_256_GCM.getCipherName(), "pwd");
        enc.setForUdp(true);
        dec.setForUdp(true);
        CompositeByteBuf input = PooledByteBufAllocator.DEFAULT.compositeDirectBuffer(2);
        ByteBuf encrypted = null;
        ByteBuf decrypted = null;
        try {
            input.addComponent(true, Unpooled.copiedBuffer("udp-", StandardCharsets.UTF_8));
            input.addComponent(true, Unpooled.copiedBuffer("composite", StandardCharsets.UTF_8));
            encrypted = enc.encrypt(input);
            decrypted = dec.decrypt(encrypted);
            assertEquals("udp-composite", decrypted.toString(StandardCharsets.UTF_8));
        } finally {
            release(input, encrypted, decrypted);
            enc.close();
            dec.close();
        }
    }

    @Test
    void outputHeapBufferCanBeRequested() {
        Aes256GcmByteBufCrypto enc = new Aes256GcmByteBufCrypto(CipherKind.AES_256_GCM.getCipherName(), "pwd", false);
        Aes256GcmByteBufCrypto dec = new Aes256GcmByteBufCrypto(CipherKind.AES_256_GCM.getCipherName(), "pwd", false);
        enc.setForUdp(false);
        dec.setForUdp(false);
        ByteBuf input = Unpooled.copiedBuffer("heap-output", StandardCharsets.UTF_8);
        ByteBuf encrypted = null;
        ByteBuf decrypted = null;
        try {
            encrypted = enc.encrypt(input);
            assertFalse(encrypted.isDirect());
            decrypted = dec.decrypt(encrypted);
            assertEquals("heap-output", decrypted.toString(StandardCharsets.UTF_8));
        } finally {
            release(input, encrypted, decrypted);
            enc.close();
            dec.close();
        }
    }

    @Test
    void aes128Gcm_tcpAndUdpRoundTrip() {
        Aes128GcmByteBufCrypto tcpEnc = new Aes128GcmByteBufCrypto(CipherKind.AES_128_GCM.getCipherName(), "pwd");
        Aes128GcmByteBufCrypto tcpDec = new Aes128GcmByteBufCrypto(CipherKind.AES_128_GCM.getCipherName(), "pwd");
        ICrypto udpEnc = ICrypto.get(CipherKind.AES_128_GCM.getCipherName(), "pwd", true);
        ICrypto udpDec = ICrypto.get(CipherKind.AES_128_GCM.getCipherName(), "pwd", true);
        tcpEnc.setForUdp(false);
        tcpDec.setForUdp(false);
        ByteBuf tcpInput = Unpooled.copiedBuffer("aes128-tcp", StandardCharsets.UTF_8);
        ByteBuf udpInput = Unpooled.copiedBuffer("aes128-udp", StandardCharsets.UTF_8);
        ByteBuf tcpEncrypted = null;
        ByteBuf tcpDecrypted = null;
        ByteBuf udpEncrypted = null;
        ByteBuf udpDecrypted = null;
        try {
            tcpEncrypted = tcpEnc.encrypt(tcpInput);
            tcpDecrypted = tcpDec.decrypt(tcpEncrypted);
            assertEquals("aes128-tcp", tcpDecrypted.toString(StandardCharsets.UTF_8));

            udpEncrypted = udpEnc.encrypt(udpInput);
            udpDecrypted = udpDec.decrypt(udpEncrypted);
            assertEquals("aes128-udp", udpDecrypted.toString(StandardCharsets.UTF_8));
        } finally {
            release(tcpInput, tcpEncrypted, tcpDecrypted, udpInput, udpEncrypted, udpDecrypted);
            close(tcpEnc, tcpDec, udpEnc, udpDec);
        }
    }

    @Test
    void decrypt_rejectsTamperedUdpPacket() {
        ICrypto enc = ICrypto.get(CipherKind.AES_256_GCM.getCipherName(), "pwd", true);
        ICrypto dec = ICrypto.get(CipherKind.AES_256_GCM.getCipherName(), "pwd", true);
        ByteBuf input = Unpooled.copiedBuffer("auth-fail", StandardCharsets.UTF_8);
        ByteBuf encrypted = null;
        try {
            encrypted = enc.encrypt(input);
            encrypted.setByte(encrypted.writerIndex() - 1, encrypted.getByte(encrypted.writerIndex() - 1) ^ 1);
            ByteBuf packet = encrypted;
            assertThrows(DecoderException.class, () -> dec.decrypt(packet));
        } finally {
            release(input, encrypted);
            close(enc, dec);
        }
    }

    @Test
    void cipherKind_rejectsRemovedCiphers() {
        assertThrows(IllegalArgumentException.class, () -> ICrypto.get("aes-192-gcm", "pwd"));
    }

    @Test
    void chacha20Poly1305_tcpAndUdpRoundTrip() {
        ICrypto tcpEnc = ICrypto.get(CipherKind.CHACHA20_IETF_POLY1305.getCipherName(), "pwd");
        ICrypto tcpDec = ICrypto.get(CipherKind.CHACHA20_IETF_POLY1305.getCipherName(), "pwd");
        ICrypto udpEnc = ICrypto.get(CipherKind.CHACHA20_IETF_POLY1305.getCipherName(), "pwd", true);
        ICrypto udpDec = ICrypto.get(CipherKind.CHACHA20_IETF_POLY1305.getCipherName(), "pwd", true);
        ByteBuf tcpInput = Unpooled.copiedBuffer("chacha-tcp", StandardCharsets.UTF_8);
        ByteBuf udpInput = Unpooled.copiedBuffer("chacha-udp", StandardCharsets.UTF_8);
        ByteBuf tcpEncrypted = null;
        ByteBuf tcpDecrypted = null;
        ByteBuf udpEncrypted = null;
        ByteBuf udpDecrypted = null;
        try {
            tcpEncrypted = tcpEnc.encrypt(tcpInput);
            tcpDecrypted = tcpDec.decrypt(tcpEncrypted);
            assertEquals("chacha-tcp", tcpDecrypted.toString(StandardCharsets.UTF_8));

            udpEncrypted = udpEnc.encrypt(udpInput);
            udpDecrypted = udpDec.decrypt(udpEncrypted);
            assertEquals("chacha-udp", udpDecrypted.toString(StandardCharsets.UTF_8));
        } finally {
            release(tcpInput, tcpEncrypted, tcpDecrypted, udpInput, udpEncrypted, udpDecrypted);
            close(tcpEnc, tcpDec, udpEnc, udpDec);
        }
    }

    private static byte[] payload(int len) {
        byte[] payload = new byte[len];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) i;
        }
        return payload;
    }

    private static byte[] readBytes(ByteBuf buf) {
        byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        return bytes;
    }

    private static void assertTcpDecryptSupportsSaltSplit(ICrypto enc, ICrypto dec, String message, int saltLength) {
        ByteBuf input = Unpooled.copiedBuffer(message, StandardCharsets.UTF_8);
        ByteBuf encrypted = null;
        ByteBuf part1 = null;
        ByteBuf part2 = null;
        ByteBuf part3 = null;
        ByteBuf out1 = null;
        ByteBuf out2 = null;
        ByteBuf out3 = null;
        try {
            encrypted = enc.encrypt(input);
            part1 = encrypted.retainedSlice(0, 7);
            part2 = encrypted.retainedSlice(7, saltLength - 7);
            part3 = encrypted.retainedSlice(saltLength, encrypted.readableBytes() - saltLength);

            out1 = dec.decrypt(part1);
            assertFalse(out1.isReadable());
            out2 = dec.decrypt(part2);
            assertFalse(out2.isReadable());
            out3 = dec.decrypt(part3);
            assertEquals(message, out3.toString(StandardCharsets.UTF_8));
        } finally {
            release(input, encrypted, part1, part2, part3, out1, out2, out3);
            close(enc, dec);
        }
    }

    private static byte[] aesGcmTcpPacketByJce(String password, byte[] salt, byte[] plaintext, int keyLength) throws Exception {
        byte[] subkey = hkdfSha1(evpBytesToKey(password, keyLength), salt, keyLength);
        byte[] nonce = new byte[12];
        byte[] len = new byte[]{(byte) (plaintext.length >>> 8), (byte) plaintext.length};
        byte[] lenChunk = aesGcm(subkey, nonce, len);
        increment(nonce);
        byte[] payloadChunk = aesGcm(subkey, nonce, plaintext);

        byte[] packet = new byte[salt.length + lenChunk.length + payloadChunk.length];
        int offset = 0;
        System.arraycopy(salt, 0, packet, offset, salt.length);
        offset += salt.length;
        System.arraycopy(lenChunk, 0, packet, offset, lenChunk.length);
        offset += lenChunk.length;
        System.arraycopy(payloadChunk, 0, packet, offset, payloadChunk.length);
        return packet;
    }

    private static byte[] aesGcm(byte[] key, byte[] nonce, byte[] plaintext) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        return cipher.doFinal(plaintext);
    }

    private static byte[] hkdfSha1(byte[] secret, byte[] salt, int length) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(salt, "HmacSHA1"));
        byte[] prk = mac.doFinal(secret);
        byte[] okm = new byte[length];
        byte[] previous = new byte[0];
        byte[] info = "ss-subkey".getBytes(StandardCharsets.US_ASCII);
        int copied = 0;
        int counter = 1;
        while (copied < length) {
            mac.init(new SecretKeySpec(prk, "HmacSHA1"));
            mac.update(previous);
            mac.update(info);
            mac.update((byte) counter++);
            previous = mac.doFinal();
            int copy = Math.min(previous.length, length - copied);
            System.arraycopy(previous, 0, okm, copied, copy);
            copied += copy;
        }
        return okm;
    }

    private static byte[] evpBytesToKey(String password, int length) throws Exception {
        MessageDigest md5 = MessageDigest.getInstance("MD5");
        byte[] passwordBytes = password.getBytes(StandardCharsets.UTF_8);
        byte[] keys = new byte[length];
        byte[] previous = new byte[0];
        int offset = 0;
        while (offset < keys.length) {
            md5.reset();
            md5.update(previous);
            md5.update(passwordBytes);
            previous = md5.digest();
            int copy = Math.min(previous.length, keys.length - offset);
            System.arraycopy(previous, 0, keys, offset, copy);
            offset += copy;
        }
        return length == keys.length ? keys : Arrays.copyOf(keys, length);
    }

    private static void increment(byte[] nonce) {
        for (int i = 0; i < nonce.length; i++) {
            if (++nonce[i] != 0) {
                break;
            }
        }
    }

    private static void release(ByteBuf... buffers) {
        for (ByteBuf buf : buffers) {
            if (buf != null && buf.refCnt() > 0) {
                buf.release();
            }
        }
    }

    private static void close(ICrypto... cryptos) {
        for (ICrypto crypto : cryptos) {
            if (crypto instanceof AutoCloseable) {
                try {
                    ((AutoCloseable) crypto).close();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        }
    }
}
