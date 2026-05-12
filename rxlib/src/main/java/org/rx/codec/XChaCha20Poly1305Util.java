package org.rx.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.util.concurrent.FastThreadLocal;
import org.rx.core.Linq;
import org.rx.core.RxConfig;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

public class XChaCha20Poly1305Util {
    private static final int KEY_LEN = ChaCha20Engine.KEY_SIZE_BYTES;
    private static final int NONCE_LEN = ChaCha20Engine.X_NONCE_SIZE_BYTES;
    private static final int NONCE12_LEN = ChaCha20Engine.IETF_NONCE_SIZE_BYTES;
    private static final int BLOCK_SIZE = ChaCha20Engine.BLOCK_SIZE_BYTES;
    private static final int TAG_LEN = 16;
    private static final int POLY_LIMB_MASK = 0x3ffffff;

    private static final FastThreadLocal<byte[]> NONCE_BUF = new FastThreadLocal<byte[]>() {
        @Override
        protected byte[] initialValue() {
            return new byte[NONCE_LEN];
        }
    };
    private static final FastThreadLocal<byte[]> SUB_KEY_BUF = new FastThreadLocal<byte[]>() {
        @Override
        protected byte[] initialValue() {
            return new byte[KEY_LEN];
        }
    };
    private static final FastThreadLocal<byte[]> NONCE12_BUF = new FastThreadLocal<byte[]>() {
        @Override
        protected byte[] initialValue() {
            return new byte[NONCE12_LEN];
        }
    };
    private static final FastThreadLocal<Poly1305State> POLY_STATE = new FastThreadLocal<Poly1305State>() {
        @Override
        protected Poly1305State initialValue() {
            return new Poly1305State();
        }
    };
    private static final FastThreadLocal<MessageDigest> KEY_DIGEST = new FastThreadLocal<MessageDigest>() {
        @Override
        protected MessageDigest initialValue() throws Exception {
            return MessageDigest.getInstance("SHA-256");
        }
    };

    public static byte[] encrypt(byte[] key, byte[] plaintext) {
        if (plaintext == null) {
            throw new IllegalArgumentException("Plaintext is null");
        }
        ByteBuf in = Unpooled.wrappedBuffer(plaintext);
        ByteBuf out = PooledByteBufAllocator.DEFAULT.heapBuffer(NONCE_LEN + plaintext.length + TAG_LEN);
        try {
            encrypt(key, in, out);
            return readBytes(out);
        } finally {
            in.release();
            out.release();
        }
    }

    public static ByteBuf encrypt(byte[] key, ByteBuf plaintext, ByteBuf out) {
        checkKey(key);
        if (plaintext == null || out == null) {
            throw new IllegalArgumentException("Input or output is null");
        }
        int length = plaintext.readableBytes();
        out.ensureWritable(NONCE_LEN + length + TAG_LEN);
        int outIndex = out.writerIndex();
        byte[] nonce = NONCE_BUF.get();
        byte[] subKey = SUB_KEY_BUF.get();
        byte[] nonce12 = NONCE12_BUF.get();
        ByteBuf firstBlock = pooledBuffer(BLOCK_SIZE);
        ByteBuf lenBlock = pooledBuffer(TAG_LEN);
        ByteBuf partialBlock = pooledBuffer(TAG_LEN);
        try {
            CodecUtil.threadLocalSecureRandom().nextBytes(nonce);
            ChaCha20Engine.xChaCha20SubKey(key, nonce, subKey);
            ChaCha20Engine.xChaCha20IetfNonce(nonce, nonce12);

            out.writeBytes(nonce);
            int cipherIndex = outIndex + NONCE_LEN;
            ChaCha20Engine.xor(subKey, nonce12, 1, plaintext, plaintext.readerIndex(), length, out, cipherIndex);
            writeTag(subKey, nonce12, out, cipherIndex, length, out, cipherIndex + length,
                    firstBlock, lenBlock, partialBlock);
            out.writerIndex(outIndex + NONCE_LEN + length + TAG_LEN);
            return out;
        } finally {
            Arrays.fill(nonce, (byte) 0);
            Arrays.fill(subKey, (byte) 0);
            Arrays.fill(nonce12, (byte) 0);
            release(firstBlock, lenBlock, partialBlock);
        }
    }

    public static byte[] decrypt(byte[] key, byte[] input) {
        if (input == null) {
            throw new IllegalArgumentException("Input is null");
        }
        ByteBuf in = Unpooled.wrappedBuffer(input);
        ByteBuf out = PooledByteBufAllocator.DEFAULT.heapBuffer(Math.max(0, input.length - NONCE_LEN - TAG_LEN));
        try {
            decrypt(key, in, out);
            return readBytes(out);
        } finally {
            in.release();
            out.release();
        }
    }

    public static ByteBuf decrypt(byte[] key, ByteBuf input, ByteBuf out) {
        checkKey(key);
        if (input == null || out == null) {
            throw new IllegalArgumentException("Input or output is null");
        }
        int length = input.readableBytes();
        if (length < NONCE_LEN + TAG_LEN) {
            throw new IllegalArgumentException("Input too short");
        }

        int inputIndex = input.readerIndex();
        int cipherLen = length - NONCE_LEN - TAG_LEN;
        int cipherIndex = inputIndex + NONCE_LEN;
        int tagIndex = cipherIndex + cipherLen;
        byte[] subKey = SUB_KEY_BUF.get();
        byte[] nonce12 = NONCE12_BUF.get();
        ByteBuf firstBlock = pooledBuffer(BLOCK_SIZE);
        ByteBuf lenBlock = pooledBuffer(TAG_LEN);
        ByteBuf partialBlock = pooledBuffer(TAG_LEN);
        ByteBuf expectedTag = pooledBuffer(TAG_LEN);
        try {
            ChaCha20Engine.xChaCha20SubKey(key, input, inputIndex, subKey);
            ChaCha20Engine.xChaCha20IetfNonce(input, inputIndex, nonce12);
            verifyTag(subKey, nonce12, input, cipherIndex, cipherLen, tagIndex,
                    firstBlock, lenBlock, partialBlock, expectedTag);

            out.ensureWritable(cipherLen);
            int outIndex = out.writerIndex();
            ChaCha20Engine.xor(subKey, nonce12, 1, input, cipherIndex, cipherLen, out, outIndex);
            out.writerIndex(outIndex + cipherLen);
            return out;
        } finally {
            Arrays.fill(subKey, (byte) 0);
            Arrays.fill(nonce12, (byte) 0);
            release(firstBlock, lenBlock, partialBlock, expectedTag);
        }
    }

    public static byte[] generateKey() {
        return CodecUtil.secureRandomBytes(KEY_LEN);
    }

    public static byte[] generateKey(String seed) {
        if (seed == null) {
            throw new IllegalArgumentException("Key seed is null");
        }
        return generateKey(seed.getBytes(StandardCharsets.UTF_8));
    }

    public static byte[] generateKey(byte[] seed) {
        if (seed == null) {
            throw new IllegalArgumentException("Key seed is null");
        }
        MessageDigest digest = KEY_DIGEST.get();
        digest.reset();
        return digest.digest(seed);
    }

    public static byte[] encrypt(byte[] plaintext) {
        return encrypt(defaultKey(), plaintext);
    }

    public static ByteBuf encrypt(ByteBuf plaintext, ByteBuf out) {
        return encrypt(defaultKey(), plaintext, out);
    }

    public static byte[] decrypt(byte[] input) {
        return decrypt(defaultKey(), input);
    }

    public static ByteBuf decrypt(ByteBuf input, ByteBuf out) {
        return decrypt(defaultKey(), input, out);
    }

    private static byte[] defaultKey() {
        return Linq.from(RxConfig.INSTANCE.getNet().getCiphers()).where(p -> p.startsWith("2,"))
                .select(p -> CodecUtil.convertFromBase64(p.substring(2))).first();
    }

    private static void checkKey(byte[] key) {
        if (key == null || key.length != KEY_LEN) {
            throw new IllegalArgumentException("Key must be " + KEY_LEN + " bytes");
        }
    }

    private static void writeTag(byte[] subKey, byte[] nonce12, ByteBuf ciphertext, int index, int length,
                                 ByteBuf tagOut, int tagIndex, ByteBuf firstBlock, ByteBuf lenBlock,
                                 ByteBuf partialBlock) {
        scratch(firstBlock, BLOCK_SIZE);
        ChaCha20Engine.block(subKey, nonce12, 0, firstBlock, 0);
        poly1305(firstBlock, ciphertext, index, length, tagOut, tagIndex, lenBlock, partialBlock);
    }

    private static void verifyTag(byte[] subKey, byte[] nonce12, ByteBuf input, int cipherIndex,
                                  int cipherLen, int tagIndex, ByteBuf firstBlock, ByteBuf lenBlock,
                                  ByteBuf partialBlock, ByteBuf expected) {
        scratch(expected, TAG_LEN);
        writeTag(subKey, nonce12, input, cipherIndex, cipherLen, expected, 0,
                firstBlock, lenBlock, partialBlock);
        int diff = 0;
        for (int i = 0; i < TAG_LEN; i++) {
            diff |= expected.getByte(i) ^ input.getByte(tagIndex + i);
        }
        if (diff != 0) {
            throw new SecurityException("Tag mismatch");
        }
    }

    private static void poly1305(ByteBuf key, ByteBuf ciphertext, int index, int length,
                                 ByteBuf tagOut, int tagIndex, ByteBuf lenBlock, ByteBuf partialBlock) {
        Poly1305State state = POLY_STATE.get();
        state.init(key, 0);
        state.updatePadded(ciphertext, index, length, partialBlock);

        zeroScratch(lenBlock, TAG_LEN);
        writeLongLE(0, lenBlock, 0);
        writeLongLE(length, lenBlock, 8);
        state.processBlock(lenBlock, 0, true);
        state.finish(tagOut, tagIndex);
    }

    private static byte[] readBytes(ByteBuf buf) {
        byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        return bytes;
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

    private static ByteBuf pooledBuffer(int capacity) {
        ByteBuf buf = PooledByteBufAllocator.DEFAULT.directBuffer(capacity, capacity);
        buf.writerIndex(capacity);
        return buf;
    }

    private static ByteBuf scratch(ByteBuf buf, int length) {
        buf.clear();
        buf.writerIndex(length);
        return buf;
    }

    private static ByteBuf zeroScratch(ByteBuf buf, int length) {
        scratch(buf, length);
        buf.setZero(0, length);
        return buf;
    }

    private static void release(ByteBuf... buffers) {
        for (ByteBuf buf : buffers) {
            if (buf != null && buf.refCnt() > 0) {
                buf.release();
            }
        }
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

        void updatePadded(ByteBuf in, int index, int length, ByteBuf partialBlock) {
            if (length <= 0) {
                return;
            }
            int remaining = length;
            int src = index;
            while (remaining >= TAG_LEN) {
                processBlock(in, src, true);
                src += TAG_LEN;
                remaining -= TAG_LEN;
            }
            if (remaining > 0) {
                ByteBuf block = zeroScratch(partialBlock, TAG_LEN);
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
