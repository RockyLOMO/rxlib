package org.rx.net.socks.encryption.impl;

import io.netty.buffer.ByteBuf;
import io.netty.util.concurrent.FastThreadLocal;
import org.rx.codec.ChaCha20Engine;

import javax.crypto.AEADBadTagException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.Arrays;

final class ChaCha20Poly1305Support {
    private static final int BLOCK_SIZE = ChaCha20Engine.BLOCK_SIZE_BYTES;
    private static final int TAG_LENGTH = 16;
    private static final BigInteger P = BigInteger.ONE.shiftLeft(130).subtract(BigInteger.valueOf(5));
    private static final BigInteger MASK_128 = BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE);
    private static final FastThreadLocal<byte[]> CHACHA_BLOCK = new FastThreadLocal<byte[]>() {
        @Override
        protected byte[] initialValue() {
            return new byte[BLOCK_SIZE];
        }
    };
    private static final FastThreadLocal<byte[]> POLY_KEY = new FastThreadLocal<byte[]>() {
        @Override
        protected byte[] initialValue() {
            return new byte[32];
        }
    };
    private static final FastThreadLocal<byte[]> POLY_BLOCK = new FastThreadLocal<byte[]>() {
        @Override
        protected byte[] initialValue() {
            return new byte[17];
        }
    };
    private static final FastThreadLocal<byte[]> TAG_BUF = new FastThreadLocal<byte[]>() {
        @Override
        protected byte[] initialValue() {
            return new byte[TAG_LENGTH];
        }
    };

    private ChaCha20Poly1305Support() {
    }

    static int encrypt(byte[] key, byte[] nonce, ByteBuffer input, int length, ByteBuf out) {
        out.ensureWritable(length + TAG_LENGTH);
        int outIndex = out.writerIndex();
        xor(key, nonce, input, length, out, outIndex);
        byte[] tag = tag(key, nonce, out, outIndex, length);
        out.setBytes(outIndex + length, tag, 0, TAG_LENGTH);
        out.writerIndex(outIndex + length + TAG_LENGTH);
        return length + TAG_LENGTH;
    }

    static int encrypt(byte[] key, byte[] nonce, ByteBuf in, int index, int length, ByteBuf out) {
        out.ensureWritable(length + TAG_LENGTH);
        int outIndex = out.writerIndex();
        xor(key, nonce, in, index, length, out, outIndex);
        byte[] tag = tag(key, nonce, out, outIndex, length);
        out.setBytes(outIndex + length, tag, 0, TAG_LENGTH);
        out.writerIndex(outIndex + length + TAG_LENGTH);
        return length + TAG_LENGTH;
    }

    static int decrypt(byte[] key, byte[] nonce, ByteBuf in, int index, int length, ByteBuffer out)
            throws AEADBadTagException {
        int plainLen = length - TAG_LENGTH;
        verify(key, nonce, in, index, plainLen, index + plainLen);
        xor(key, nonce, in, index, plainLen, out);
        return plainLen;
    }

    static int decrypt(byte[] key, byte[] nonce, ByteBuf in, int index, int length, ByteBuf out)
            throws AEADBadTagException {
        int plainLen = length - TAG_LENGTH;
        verify(key, nonce, in, index, plainLen, index + plainLen);
        out.ensureWritable(plainLen);
        int outIndex = out.writerIndex();
        xor(key, nonce, in, index, plainLen, out, outIndex);
        out.writerIndex(outIndex + plainLen);
        return plainLen;
    }

    private static void verify(byte[] key, byte[] nonce, ByteBuf in, int cipherIndex, int cipherLen, int tagIndex)
            throws AEADBadTagException {
        byte[] expected = tag(key, nonce, in, cipherIndex, cipherLen);
        int diff = 0;
        for (int i = 0; i < TAG_LENGTH; i++) {
            diff |= expected[i] ^ in.getByte(tagIndex + i);
        }
        if (diff != 0) {
            throw new AEADBadTagException("Tag mismatch");
        }
    }

    private static void xor(byte[] key, byte[] nonce, ByteBuffer input, int length, ByteBuf out, int outIndex) {
        byte[] block = CHACHA_BLOCK.get();
        int remaining = length;
        int counter = 1;
        int dst = outIndex;
        while (remaining > 0) {
            ChaCha20Engine.block(key, nonce, counter++, block);
            int n = Math.min(remaining, BLOCK_SIZE);
            for (int i = 0; i < n; i++) {
                out.setByte(dst + i, (byte) (input.get() ^ block[i]));
            }
            dst += n;
            remaining -= n;
        }
    }

    private static void xor(byte[] key, byte[] nonce, ByteBuf in, int index, int length, ByteBuf out, int outIndex) {
        byte[] block = CHACHA_BLOCK.get();
        int remaining = length;
        int counter = 1;
        int src = index;
        int dst = outIndex;
        while (remaining > 0) {
            ChaCha20Engine.block(key, nonce, counter++, block);
            int n = Math.min(remaining, BLOCK_SIZE);
            for (int i = 0; i < n; i++) {
                out.setByte(dst + i, (byte) (in.getByte(src + i) ^ block[i]));
            }
            src += n;
            dst += n;
            remaining -= n;
        }
    }

    private static void xor(byte[] key, byte[] nonce, ByteBuf in, int index, int length, ByteBuffer out) {
        byte[] block = CHACHA_BLOCK.get();
        int remaining = length;
        int counter = 1;
        int src = index;
        while (remaining > 0) {
            ChaCha20Engine.block(key, nonce, counter++, block);
            int n = Math.min(remaining, BLOCK_SIZE);
            for (int i = 0; i < n; i++) {
                out.put((byte) (in.getByte(src + i) ^ block[i]));
            }
            src += n;
            remaining -= n;
        }
    }

    private static byte[] tag(byte[] key, byte[] nonce, ByteBuf ciphertext, int index, int length) {
        byte[] polyKey = POLY_KEY.get();
        byte[] firstBlock = CHACHA_BLOCK.get();
        ChaCha20Engine.block(key, nonce, 0, firstBlock);
        System.arraycopy(firstBlock, 0, polyKey, 0, polyKey.length);
        return poly1305(polyKey, ciphertext, index, length);
    }

    private static byte[] poly1305(byte[] key, ByteBuf ciphertext, int index, int length) {
        byte[] rBytes = POLY_BLOCK.get();
        Arrays.fill(rBytes, (byte) 0);
        System.arraycopy(key, 0, rBytes, 0, TAG_LENGTH);
        rBytes[3] &= 15;
        rBytes[7] &= 15;
        rBytes[11] &= 15;
        rBytes[15] &= 15;
        rBytes[4] &= 252;
        rBytes[8] &= 252;
        rBytes[12] &= 252;

        BigInteger r = littleEndian(rBytes, 0, TAG_LENGTH);
        BigInteger s = littleEndian(key, TAG_LENGTH, TAG_LENGTH);
        BigInteger acc = BigInteger.ZERO;
        int remaining = length;
        int src = index;
        byte[] block = POLY_BLOCK.get();
        while (remaining >= TAG_LENGTH) {
            Arrays.fill(block, (byte) 0);
            ciphertext.getBytes(src, block, 0, TAG_LENGTH);
            block[TAG_LENGTH] = 1;
            acc = acc.add(littleEndian(block, 0, TAG_LENGTH + 1)).multiply(r).mod(P);
            src += TAG_LENGTH;
            remaining -= TAG_LENGTH;
        }
        if (remaining > 0) {
            Arrays.fill(block, (byte) 0);
            ciphertext.getBytes(src, block, 0, remaining);
            block[TAG_LENGTH] = 1;
            acc = acc.add(littleEndian(block, 0, TAG_LENGTH + 1)).multiply(r).mod(P);
        }

        byte[] lenBlock = POLY_BLOCK.get();
        Arrays.fill(lenBlock, (byte) 0);
        writeLongLE(length, lenBlock, 8);
        lenBlock[TAG_LENGTH] = 1;
        acc = acc.add(littleEndian(lenBlock, 0, TAG_LENGTH + 1)).multiply(r).mod(P);

        byte[] tag = TAG_BUF.get();
        toLittleEndian(acc.add(s).and(MASK_128), tag, TAG_LENGTH);
        return tag;
    }

    private static BigInteger littleEndian(byte[] b, int off, int len) {
        byte[] be = new byte[len + 1];
        for (int i = 0; i < len; i++) {
            be[len - i] = b[off + i];
        }
        return new BigInteger(be);
    }

    private static void toLittleEndian(BigInteger value, byte[] out, int len) {
        byte[] be = value.toByteArray();
        Arrays.fill(out, 0, len, (byte) 0);
        for (int i = 0; i < len; i++) {
            int src = be.length - 1 - i;
            if (src >= 0) {
                out[i] = be[src];
            }
        }
    }

    private static void writeLongLE(long value, byte[] out, int off) {
        for (int i = 0; i < 8; i++) {
            out[off + i] = (byte) (value >>> (i << 3));
        }
    }
}
