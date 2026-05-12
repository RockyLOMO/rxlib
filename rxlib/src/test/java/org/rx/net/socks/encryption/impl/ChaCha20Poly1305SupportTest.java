package org.rx.net.socks.encryption.impl;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import javax.crypto.AEADBadTagException;
import org.junit.jupiter.api.Test;
import org.rx.codec.CodecUtil;
import org.rx.io.Bytes;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    @Test
    void encrypt_matchesRfc8439AeadVectorWithAad() throws Exception {
        byte[] key = CodecUtil.fromHex("808182838485868788898a8b8c8d8e8f"
                + "909192939495969798999a9b9c9d9e9f");
        byte[] nonce = CodecUtil.fromHex("070000004041424344454647");
        ByteBuf aad = Unpooled.wrappedBuffer(CodecUtil.fromHex("50515253c0c1c2c3c4c5c6c7"));
        ByteBuf input = Unpooled.wrappedBuffer(CodecUtil.fromHex("4c616469657320616e642047656e746c656d656e206f662074"
                + "686520636c617373206f66202739393a204966204920636f756c64206f6666657220796f75206f6e6c7920"
                + "6f6e652074697020666f7220746865206675747572652c2073756e73637265656e20776f756c6420626520"
                + "69742e"));
        ByteBuf encrypted = Bytes.directBuffer(160);
        ByteBuf decrypted = Bytes.directBuffer(128);
        try {
            ChaCha20Poly1305Support.encrypt(key, nonce, aad, aad.readerIndex(), aad.readableBytes(),
                    input, input.readerIndex(), input.readableBytes(), encrypted);
            assertEquals("d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d"
                            + "63dbea45e8ca9671282fafb69da92728b1a71de0a9e060b2905d6a5b67ecd3b3692dd"
                            + "bd7f2d778b8c9803aee328091b58fab324e4fad675945585808b4831d7bc3ff4def08"
                            + "e4b7a9de576d26586cec64b61161ae10b594f09e26a7e902ecbd0600691",
                    CodecUtil.toHex(readBytes(encrypted.slice())));

            ChaCha20Poly1305Support.decrypt(key, nonce, aad, aad.readerIndex(), aad.readableBytes(),
                    encrypted, encrypted.readerIndex(), encrypted.readableBytes(), decrypted);
            assertArrayEquals(readBytes(input.slice()), readBytes(decrypted));
        } finally {
            aad.release();
            input.release();
            encrypted.release();
            decrypted.release();
        }
    }

    @Test
    void encryptDecrypt_coversBoundaryLengthsWithAad() throws Exception {
        byte[] key = payload(32, 0x20);
        byte[] nonce = payload(12, 0x50);
        int[] lengths = new int[]{0, 1, 15, 16, 17, 63, 64, 65, 1024, 4097};
        for (int length : lengths) {
            ByteBuf aad = Unpooled.wrappedBuffer(payload(19, 0x70));
            ByteBuf input = Unpooled.wrappedBuffer(payload(length, 0x90));
            ByteBuf encrypted = Bytes.directBuffer(length + 16);
            ByteBuf decrypted = Bytes.directBuffer(length);
            try {
                ChaCha20Poly1305Support.encrypt(key, nonce, aad, aad.readerIndex(), aad.readableBytes(),
                        input, input.readerIndex(), input.readableBytes(), encrypted);
                ChaCha20Poly1305Support.decrypt(key, nonce, aad, aad.readerIndex(), aad.readableBytes(),
                        encrypted, encrypted.readerIndex(), encrypted.readableBytes(), decrypted);
                assertArrayEquals(readBytes(input.slice()), readBytes(decrypted), "length=" + length);
            } finally {
                aad.release();
                input.release();
                encrypted.release();
                decrypted.release();
            }
        }
    }

    @Test
    void decrypt_rejectsTamperedTagAndShortPacket() throws Exception {
        byte[] key = payload(32, 1);
        byte[] nonce = payload(12, 2);
        ByteBuf input = Unpooled.copiedBuffer("tamper", StandardCharsets.UTF_8);
        ByteBuf encrypted = Bytes.directBuffer(64);
        ByteBuf decrypted = Bytes.directBuffer(64);
        try {
            ChaCha20Poly1305Support.encrypt(key, nonce, input, input.readerIndex(), input.readableBytes(), encrypted);
            encrypted.setByte(encrypted.writerIndex() - 1, encrypted.getByte(encrypted.writerIndex() - 1) ^ 1);
            assertThrows(AEADBadTagException.class,
                    () -> ChaCha20Poly1305Support.decrypt(key, nonce, encrypted,
                            encrypted.readerIndex(), encrypted.readableBytes(), decrypted));
            assertThrows(AEADBadTagException.class,
                    () -> ChaCha20Poly1305Support.decrypt(key, nonce, encrypted, encrypted.readerIndex(), 15, decrypted));
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

    private static byte[] payload(int len, int base) {
        byte[] payload = new byte[len];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (base + i);
        }
        return payload;
    }
}
