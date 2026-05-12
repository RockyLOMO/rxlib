package org.rx.net.socks.encryption;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import org.junit.jupiter.api.Test;
import org.rx.net.socks.encryption.impl.Aes256GcmByteBufCrypto;

import java.nio.charset.StandardCharsets;

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
        assertThrows(IllegalArgumentException.class, () -> ICrypto.get("aes-128-gcm", "pwd"));
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
