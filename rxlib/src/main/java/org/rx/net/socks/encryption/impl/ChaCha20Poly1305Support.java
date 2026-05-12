package org.rx.net.socks.encryption.impl;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.util.concurrent.FastThreadLocal;
import org.rx.codec.ChaCha20Engine;

import javax.crypto.AEADBadTagException;
import java.nio.ByteBuffer;

final class ChaCha20Poly1305Support {
    private static final int BLOCK_SIZE = ChaCha20Engine.BLOCK_SIZE_BYTES;
    private static final int TAG_LENGTH = 16;
    private static final int POLY_LIMB_MASK = 0x3ffffff;
    private static final FastThreadLocal<ByteBuf> CHACHA_BLOCK = pooledBuffer(BLOCK_SIZE);
    private static final FastThreadLocal<ByteBuf> PARTIAL_BLOCK = pooledBuffer(TAG_LENGTH);
    private static final FastThreadLocal<ByteBuf> LEN_BLOCK = pooledBuffer(TAG_LENGTH);
    private static final FastThreadLocal<ByteBuf> TAG_BUF = pooledBuffer(TAG_LENGTH);
    private static final FastThreadLocal<Poly1305State> POLY_STATE = new FastThreadLocal<Poly1305State>() {
        @Override
        protected Poly1305State initialValue() {
            return new Poly1305State();
        }
    };

    private ChaCha20Poly1305Support() {
    }

    static int encrypt(byte[] key, byte[] nonce, ByteBuffer input, int length, ByteBuf out) {
        out.ensureWritable(length + TAG_LENGTH);
        int outIndex = out.writerIndex();
        xor(key, nonce, input, length, out, outIndex);
        writeTag(key, nonce, null, 0, 0, out, outIndex, length, out, outIndex + length);
        out.writerIndex(outIndex + length + TAG_LENGTH);
        return length + TAG_LENGTH;
    }

    static int encrypt(byte[] key, byte[] nonce, ByteBuf in, int index, int length, ByteBuf out) {
        out.ensureWritable(length + TAG_LENGTH);
        int outIndex = out.writerIndex();
        xor(key, nonce, in, index, length, out, outIndex);
        writeTag(key, nonce, null, 0, 0, out, outIndex, length, out, outIndex + length);
        out.writerIndex(outIndex + length + TAG_LENGTH);
        return length + TAG_LENGTH;
    }

    static int encrypt(byte[] key, byte[] nonce, ByteBuf aad, int aadIndex, int aadLength,
                       ByteBuf in, int index, int length, ByteBuf out) {
        out.ensureWritable(length + TAG_LENGTH);
        int outIndex = out.writerIndex();
        xor(key, nonce, in, index, length, out, outIndex);
        writeTag(key, nonce, aad, aadIndex, aadLength, out, outIndex, length, out, outIndex + length);
        out.writerIndex(outIndex + length + TAG_LENGTH);
        return length + TAG_LENGTH;
    }

    static int decrypt(byte[] key, byte[] nonce, ByteBuf in, int index, int length, ByteBuffer out)
            throws AEADBadTagException {
        if (length < TAG_LENGTH) {
            throw new AEADBadTagException("Packet too short");
        }
        int plainLen = length - TAG_LENGTH;
        verify(key, nonce, null, 0, 0, in, index, plainLen, index + plainLen);
        xor(key, nonce, in, index, plainLen, out);
        return plainLen;
    }

    static int decrypt(byte[] key, byte[] nonce, ByteBuf in, int index, int length, ByteBuf out)
            throws AEADBadTagException {
        if (length < TAG_LENGTH) {
            throw new AEADBadTagException("Packet too short");
        }
        int plainLen = length - TAG_LENGTH;
        verify(key, nonce, null, 0, 0, in, index, plainLen, index + plainLen);
        out.ensureWritable(plainLen);
        int outIndex = out.writerIndex();
        xor(key, nonce, in, index, plainLen, out, outIndex);
        out.writerIndex(outIndex + plainLen);
        return plainLen;
    }

    static int decrypt(byte[] key, byte[] nonce, ByteBuf aad, int aadIndex, int aadLength,
                       ByteBuf in, int index, int length, ByteBuf out) throws AEADBadTagException {
        if (length < TAG_LENGTH) {
            throw new AEADBadTagException("Packet too short");
        }
        int plainLen = length - TAG_LENGTH;
        verify(key, nonce, aad, aadIndex, aadLength, in, index, plainLen, index + plainLen);
        out.ensureWritable(plainLen);
        int outIndex = out.writerIndex();
        xor(key, nonce, in, index, plainLen, out, outIndex);
        out.writerIndex(outIndex + plainLen);
        return plainLen;
    }

    private static void verify(byte[] key, byte[] nonce, ByteBuf aad, int aadIndex, int aadLength,
                               ByteBuf in, int cipherIndex, int cipherLen, int tagIndex) throws AEADBadTagException {
        ByteBuf expected = scratch(TAG_BUF, TAG_LENGTH);
        writeTag(key, nonce, aad, aadIndex, aadLength, in, cipherIndex, cipherLen, expected, 0);
        int diff = 0;
        for (int i = 0; i < TAG_LENGTH; i++) {
            diff |= expected.getByte(i) ^ in.getByte(tagIndex + i);
        }
        if (diff != 0) {
            throw new AEADBadTagException("Tag mismatch");
        }
    }

    private static void xor(byte[] key, byte[] nonce, ByteBuffer input, int length, ByteBuf out, int outIndex) {
        ByteBuf block = scratch(CHACHA_BLOCK, BLOCK_SIZE);
        int remaining = length;
        int counter = 1;
        int dst = outIndex;
        while (remaining > 0) {
            ChaCha20Engine.block(key, nonce, counter++, block, 0);
            int n = Math.min(remaining, BLOCK_SIZE);
            for (int i = 0; i < n; i++) {
                out.setByte(dst + i, (byte) (input.get() ^ block.getByte(i)));
            }
            dst += n;
            remaining -= n;
        }
    }

    private static void xor(byte[] key, byte[] nonce, ByteBuf in, int index, int length, ByteBuf out, int outIndex) {
        ByteBuf block = scratch(CHACHA_BLOCK, BLOCK_SIZE);
        int remaining = length;
        int counter = 1;
        int src = index;
        int dst = outIndex;
        while (remaining > 0) {
            ChaCha20Engine.block(key, nonce, counter++, block, 0);
            int n = Math.min(remaining, BLOCK_SIZE);
            for (int i = 0; i < n; i++) {
                out.setByte(dst + i, (byte) (in.getByte(src + i) ^ block.getByte(i)));
            }
            src += n;
            dst += n;
            remaining -= n;
        }
    }

    private static void xor(byte[] key, byte[] nonce, ByteBuf in, int index, int length, ByteBuffer out) {
        ByteBuf block = scratch(CHACHA_BLOCK, BLOCK_SIZE);
        int remaining = length;
        int counter = 1;
        int src = index;
        while (remaining > 0) {
            ChaCha20Engine.block(key, nonce, counter++, block, 0);
            int n = Math.min(remaining, BLOCK_SIZE);
            for (int i = 0; i < n; i++) {
                out.put((byte) (in.getByte(src + i) ^ block.getByte(i)));
            }
            src += n;
            remaining -= n;
        }
    }

    private static void writeTag(byte[] key, byte[] nonce, ByteBuf aad, int aadIndex, int aadLength,
                                 ByteBuf ciphertext, int index, int length, ByteBuf tagOut, int tagIndex) {
        ByteBuf firstBlock = scratch(CHACHA_BLOCK, BLOCK_SIZE);
        ChaCha20Engine.block(key, nonce, 0, firstBlock, 0);
        poly1305(firstBlock, aad, aadIndex, aadLength, ciphertext, index, length, tagOut, tagIndex);
    }

    private static void poly1305(ByteBuf key, ByteBuf aad, int aadIndex, int aadLength,
                                 ByteBuf ciphertext, int index, int length, ByteBuf tagOut, int tagIndex) {
        Poly1305State state = POLY_STATE.get();
        state.init(key, 0);
        state.updatePadded(aad, aadIndex, aadLength);
        state.updatePadded(ciphertext, index, length);

        ByteBuf lenBlock = zeroScratch(LEN_BLOCK, TAG_LENGTH);
        writeLongLE(aadLength, lenBlock, 0);
        writeLongLE(length, lenBlock, 8);
        state.processBlock(lenBlock, 0, true);
        state.finish(tagOut, tagIndex);
    }

    private static void writeLongLE(long value, ByteBuf out, int off) {
        for (int i = 0; i < 8; i++) {
            out.setByte(off + i, (int) (value >>> (i << 3)));
        }
    }

    private static long le32(ByteBuf in, int off) {
        return ((long) in.getByte(off) & 0xFF)
                | (((long) in.getByte(off + 1) & 0xFF) << 8)
                | (((long) in.getByte(off + 2) & 0xFF) << 16)
                | (((long) in.getByte(off + 3) & 0xFF) << 24);
    }

    private static void writeIntLE(long value, ByteBuf out, int off) {
        out.setByte(off, (int) value);
        out.setByte(off + 1, (int) (value >>> 8));
        out.setByte(off + 2, (int) (value >>> 16));
        out.setByte(off + 3, (int) (value >>> 24));
    }

    private static FastThreadLocal<ByteBuf> pooledBuffer(final int capacity) {
        return new FastThreadLocal<ByteBuf>() {
            @Override
            protected ByteBuf initialValue() {
                return PooledByteBufAllocator.DEFAULT.directBuffer(capacity, capacity);
            }

            @Override
            protected void onRemoval(ByteBuf value) {
                value.release();
            }
        };
    }

    private static ByteBuf scratch(FastThreadLocal<ByteBuf> local, int length) {
        ByteBuf buf = local.get();
        buf.clear();
        buf.writerIndex(length);
        return buf;
    }

    private static ByteBuf zeroScratch(FastThreadLocal<ByteBuf> local, int length) {
        ByteBuf buf = scratch(local, length);
        buf.setZero(0, length);
        return buf;
    }

    private static final class Poly1305State {
        private long r0, r1, r2, r3, r4;
        private long s1, s2, s3, s4;
        private long h0, h1, h2, h3, h4;
        private long pad0, pad1, pad2, pad3;

        void init(ByteBuf key, int index) {
            long t0 = le32(key, index);
            long t1 = le32(key, index + 4);
            long t2 = le32(key, index + 8);
            long t3 = le32(key, index + 12);

            r0 = t0 & 0x3ffffffL;
            r1 = ((t0 >>> 26) | (t1 << 6)) & 0x3ffff03L;
            r2 = ((t1 >>> 20) | (t2 << 12)) & 0x3ffc0ffL;
            r3 = ((t2 >>> 14) | (t3 << 18)) & 0x3f03fffL;
            r4 = (t3 >>> 8) & 0x00fffffL;
            s1 = r1 * 5L;
            s2 = r2 * 5L;
            s3 = r3 * 5L;
            s4 = r4 * 5L;
            h0 = h1 = h2 = h3 = h4 = 0L;
            pad0 = le32(key, index + 16);
            pad1 = le32(key, index + 20);
            pad2 = le32(key, index + 24);
            pad3 = le32(key, index + 28);
        }

        void updatePadded(ByteBuf in, int index, int length) {
            if (length <= 0) {
                return;
            }
            int remaining = length;
            int src = index;
            while (remaining >= TAG_LENGTH) {
                processBlock(in, src, true);
                src += TAG_LENGTH;
                remaining -= TAG_LENGTH;
            }
            if (remaining > 0) {
                ByteBuf block = zeroScratch(PARTIAL_BLOCK, TAG_LENGTH);
                in.getBytes(src, block, 0, remaining);
                processBlock(block, 0, true);
            }
        }

        void processBlock(ByteBuf in, int index, boolean hibit) {
            long t0 = le32(in, index);
            long t1 = le32(in, index + 4);
            long t2 = le32(in, index + 8);
            long t3 = le32(in, index + 12);
            process(t0, t1, t2, t3, hibit);
        }

        private void process(long t0, long t1, long t2, long t3, boolean hibit) {
            h0 += t0 & 0x3ffffffL;
            h1 += ((t0 >>> 26) | (t1 << 6)) & 0x3ffffffL;
            h2 += ((t1 >>> 20) | (t2 << 12)) & 0x3ffffffL;
            h3 += ((t2 >>> 14) | (t3 << 18)) & 0x3ffffffL;
            h4 += (t3 >>> 8) | (hibit ? (1L << 24) : 0L);

            long d0 = h0 * r0 + h1 * s4 + h2 * s3 + h3 * s2 + h4 * s1;
            long d1 = h0 * r1 + h1 * r0 + h2 * s4 + h3 * s3 + h4 * s2;
            long d2 = h0 * r2 + h1 * r1 + h2 * r0 + h3 * s4 + h4 * s3;
            long d3 = h0 * r3 + h1 * r2 + h2 * r1 + h3 * r0 + h4 * s4;
            long d4 = h0 * r4 + h1 * r3 + h2 * r2 + h3 * r1 + h4 * r0;

            long c = d0 >>> 26;
            h0 = d0 & POLY_LIMB_MASK;
            d1 += c;
            c = d1 >>> 26;
            h1 = d1 & POLY_LIMB_MASK;
            d2 += c;
            c = d2 >>> 26;
            h2 = d2 & POLY_LIMB_MASK;
            d3 += c;
            c = d3 >>> 26;
            h3 = d3 & POLY_LIMB_MASK;
            d4 += c;
            c = d4 >>> 26;
            h4 = d4 & POLY_LIMB_MASK;
            h0 += c * 5L;
            c = h0 >>> 26;
            h0 &= POLY_LIMB_MASK;
            h1 += c;
        }

        void finish(ByteBuf tag, int index) {
            long c = h1 >>> 26;
            h1 &= POLY_LIMB_MASK;
            h2 += c;
            c = h2 >>> 26;
            h2 &= POLY_LIMB_MASK;
            h3 += c;
            c = h3 >>> 26;
            h3 &= POLY_LIMB_MASK;
            h4 += c;
            c = h4 >>> 26;
            h4 &= POLY_LIMB_MASK;
            h0 += c * 5L;
            c = h0 >>> 26;
            h0 &= POLY_LIMB_MASK;
            h1 += c;

            long g0 = h0 + 5L;
            c = g0 >>> 26;
            g0 &= POLY_LIMB_MASK;
            long g1 = h1 + c;
            c = g1 >>> 26;
            g1 &= POLY_LIMB_MASK;
            long g2 = h2 + c;
            c = g2 >>> 26;
            g2 &= POLY_LIMB_MASK;
            long g3 = h3 + c;
            c = g3 >>> 26;
            g3 &= POLY_LIMB_MASK;
            long g4 = h4 + c - (1L << 26);

            long mask = ~(g4 >> 63);
            h0 = (h0 & ~mask) | (g0 & mask);
            h1 = (h1 & ~mask) | (g1 & mask);
            h2 = (h2 & ~mask) | (g2 & mask);
            h3 = (h3 & ~mask) | (g3 & mask);
            h4 = (h4 & ~mask) | (g4 & mask);

            long f0 = (h0 | (h1 << 26)) & 0xffffffffL;
            long f1 = ((h1 >>> 6) | (h2 << 20)) & 0xffffffffL;
            long f2 = ((h2 >>> 12) | (h3 << 14)) & 0xffffffffL;
            long f3 = ((h3 >>> 18) | (h4 << 8)) & 0xffffffffL;

            f0 += pad0;
            f1 += pad1 + (f0 >>> 32);
            f2 += pad2 + (f1 >>> 32);
            f3 += pad3 + (f2 >>> 32);

            writeIntLE(f0, tag, index);
            writeIntLE(f1, tag, index + 4);
            writeIntLE(f2, tag, index + 8);
            writeIntLE(f3, tag, index + 12);
        }
    }
}
