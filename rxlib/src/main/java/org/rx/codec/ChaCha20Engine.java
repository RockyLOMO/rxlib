package org.rx.codec;

import io.netty.buffer.ByteBuf;
import io.netty.util.concurrent.FastThreadLocal;

public final class ChaCha20Engine {
    public static final int BLOCK_SIZE_BYTES = 64;
    public static final int IETF_NONCE_SIZE_BYTES = 12;
    public static final int X_NONCE_SIZE_BYTES = 24;
    public static final int KEY_SIZE_BYTES = 32;
    private static final int[] SIGMA = {
            0x61707865, 0x3320646E, 0x79622D32, 0x6B206574
    };
    private static final FastThreadLocal<int[]> STATE = new FastThreadLocal<int[]>() {
        @Override
        protected int[] initialValue() {
            return new int[16];
        }
    };
    private static final FastThreadLocal<int[]> WORKING = new FastThreadLocal<int[]>() {
        @Override
        protected int[] initialValue() {
            return new int[16];
        }
    };
    private static final FastThreadLocal<byte[]> XOR_BLOCK = new FastThreadLocal<byte[]>() {
        @Override
        protected byte[] initialValue() {
            return new byte[BLOCK_SIZE_BYTES];
        }
    };

    private ChaCha20Engine() {
    }

    public static byte[] xChaCha20SubKey(byte[] key, byte[] nonce24) {
        byte[] out = new byte[KEY_SIZE_BYTES];
        xChaCha20SubKey(key, nonce24, out);
        return out;
    }

    public static void xChaCha20SubKey(byte[] key, byte[] nonce24, byte[] out) {
        checkKey(key, "XChaCha20");
        if (nonce24 == null || nonce24.length != X_NONCE_SIZE_BYTES) {
            throw new IllegalArgumentException("XChaCha20 requires a 192 bit nonce");
        }
        checkOut(out, 0, KEY_SIZE_BYTES);
        int[] state = STATE.get();
        initHChaChaState(state, key);
        for (int i = 0; i < 4; i++) {
            state[12 + i] = littleEndianToInt(nonce24, i << 2);
        }
        rounds(state);
        intToLittleEndian(state[0], out, 0);
        intToLittleEndian(state[1], out, 4);
        intToLittleEndian(state[2], out, 8);
        intToLittleEndian(state[3], out, 12);
        intToLittleEndian(state[12], out, 16);
        intToLittleEndian(state[13], out, 20);
        intToLittleEndian(state[14], out, 24);
        intToLittleEndian(state[15], out, 28);
    }

    public static void xChaCha20SubKey(byte[] key, ByteBuf nonce24, int nonceIndex, byte[] out) {
        checkKey(key, "XChaCha20");
        checkBuffer(nonce24, nonceIndex, X_NONCE_SIZE_BYTES, "XChaCha20 requires a 192 bit nonce");
        checkOut(out, 0, KEY_SIZE_BYTES);
        int[] state = STATE.get();
        initHChaChaState(state, key);
        for (int i = 0; i < 4; i++) {
            state[12 + i] = littleEndianToInt(nonce24, nonceIndex + (i << 2));
        }
        rounds(state);
        intToLittleEndian(state[0], out, 0);
        intToLittleEndian(state[1], out, 4);
        intToLittleEndian(state[2], out, 8);
        intToLittleEndian(state[3], out, 12);
        intToLittleEndian(state[12], out, 16);
        intToLittleEndian(state[13], out, 20);
        intToLittleEndian(state[14], out, 24);
        intToLittleEndian(state[15], out, 28);
    }

    public static void xChaCha20SubKey(byte[] key, ByteBuf nonce24, int nonceIndex, ByteBuf out, int outIndex) {
        checkKey(key, "XChaCha20");
        checkBuffer(nonce24, nonceIndex, X_NONCE_SIZE_BYTES, "XChaCha20 requires a 192 bit nonce");
        checkBuffer(out, outIndex, KEY_SIZE_BYTES, "Output too short");
        int[] state = STATE.get();
        initHChaChaState(state, key);
        for (int i = 0; i < 4; i++) {
            state[12 + i] = littleEndianToInt(nonce24, nonceIndex + (i << 2));
        }
        rounds(state);
        intToLittleEndian(state[0], out, outIndex);
        intToLittleEndian(state[1], out, outIndex + 4);
        intToLittleEndian(state[2], out, outIndex + 8);
        intToLittleEndian(state[3], out, outIndex + 12);
        intToLittleEndian(state[12], out, outIndex + 16);
        intToLittleEndian(state[13], out, outIndex + 20);
        intToLittleEndian(state[14], out, outIndex + 24);
        intToLittleEndian(state[15], out, outIndex + 28);
    }

    public static byte[] xChaCha20IetfNonce(byte[] nonce24) {
        if (nonce24 == null || nonce24.length != X_NONCE_SIZE_BYTES) {
            throw new IllegalArgumentException("XChaCha20 requires a 192 bit nonce");
        }
        byte[] nonce12 = new byte[IETF_NONCE_SIZE_BYTES];
        xChaCha20IetfNonce(nonce24, nonce12);
        return nonce12;
    }

    public static void xChaCha20IetfNonce(byte[] nonce24, byte[] out) {
        if (nonce24 == null || nonce24.length != X_NONCE_SIZE_BYTES) {
            throw new IllegalArgumentException("XChaCha20 requires a 192 bit nonce");
        }
        checkOut(out, 0, IETF_NONCE_SIZE_BYTES);
        out[0] = 0;
        out[1] = 0;
        out[2] = 0;
        out[3] = 0;
        System.arraycopy(nonce24, 16, out, 4, 8);
    }

    public static void xChaCha20IetfNonce(ByteBuf nonce24, int nonceIndex, byte[] out) {
        checkBuffer(nonce24, nonceIndex, X_NONCE_SIZE_BYTES, "XChaCha20 requires a 192 bit nonce");
        checkOut(out, 0, IETF_NONCE_SIZE_BYTES);
        out[0] = 0;
        out[1] = 0;
        out[2] = 0;
        out[3] = 0;
        nonce24.getBytes(nonceIndex + 16, out, 4, 8);
    }

    public static void xChaCha20IetfNonce(ByteBuf nonce24, int nonceIndex, ByteBuf out, int outIndex) {
        checkBuffer(nonce24, nonceIndex, X_NONCE_SIZE_BYTES, "XChaCha20 requires a 192 bit nonce");
        checkBuffer(out, outIndex, IETF_NONCE_SIZE_BYTES, "Output too short");
        out.setZero(outIndex, 4);
        out.setBytes(outIndex + 4, nonce24, nonceIndex + 16, 8);
    }

    public static void xor(byte[] key, byte[] nonce12, int counter, byte[] input, int inputOffset,
                    int length, byte[] output, int outputOffset) {
        byte[] block = XOR_BLOCK.get();
        int remaining = length;
        int in = inputOffset;
        int out = outputOffset;
        int blockCounter = counter;
        while (remaining > 0) {
            block(key, nonce12, blockCounter++, block);
            int n = Math.min(remaining, BLOCK_SIZE_BYTES);
            for (int i = 0; i < n; i++) {
                output[out + i] = (byte) (input[in + i] ^ block[i]);
            }
            in += n;
            out += n;
            remaining -= n;
        }
    }

    public static void xor(byte[] key, byte[] nonce12, int counter, ByteBuf input, int inputOffset,
                           int length, ByteBuf output, int outputOffset) {
        byte[] block = XOR_BLOCK.get();
        int remaining = length;
        int in = inputOffset;
        int out = outputOffset;
        int blockCounter = counter;
        while (remaining > 0) {
            block(key, nonce12, blockCounter++, block);
            int n = Math.min(remaining, BLOCK_SIZE_BYTES);
            for (int i = 0; i < n; i++) {
                output.setByte(out + i, (byte) (input.getByte(in + i) ^ block[i]));
            }
            in += n;
            out += n;
            remaining -= n;
        }
    }

    public static void block(byte[] key, byte[] nonce12, int counter, byte[] out) {
        checkKey(key, "ChaCha20");
        if (nonce12 == null || nonce12.length != IETF_NONCE_SIZE_BYTES) {
            throw new IllegalArgumentException("ChaCha20-IETF requires a 96 bit nonce");
        }
        checkOut(out, 0, BLOCK_SIZE_BYTES);
        int[] state = STATE.get();
        int[] working = WORKING.get();
        initBlockState(state, key, nonce12, counter);
        block(state, working, out);
    }

    public static void block(byte[] key, byte[] nonce12, int counter, ByteBuf out, int outIndex) {
        checkKey(key, "ChaCha20");
        if (nonce12 == null || nonce12.length != IETF_NONCE_SIZE_BYTES) {
            throw new IllegalArgumentException("ChaCha20-IETF requires a 96 bit nonce");
        }
        checkBuffer(out, outIndex, BLOCK_SIZE_BYTES, "Output too short");
        int[] state = STATE.get();
        int[] working = WORKING.get();
        initBlockState(state, key, nonce12, counter);
        block(state, working, out, outIndex);
    }

    private static void initHChaChaState(int[] state, byte[] key) {
        state[0] = SIGMA[0];
        state[1] = SIGMA[1];
        state[2] = SIGMA[2];
        state[3] = SIGMA[3];
        for (int i = 0; i < 8; i++) {
            state[4 + i] = littleEndianToInt(key, i << 2);
        }
    }

    private static void initBlockState(int[] state, byte[] key, byte[] nonce12, int counter) {
        initHChaChaState(state, key);
        state[12] = counter;
        state[13] = littleEndianToInt(nonce12, 0);
        state[14] = littleEndianToInt(nonce12, 4);
        state[15] = littleEndianToInt(nonce12, 8);
    }

    private static void block(int[] state, int[] working, byte[] out) {
        System.arraycopy(state, 0, working, 0, state.length);
        rounds(working);
        for (int i = 0; i < working.length; i++) {
            intToLittleEndian(working[i] + state[i], out, i << 2);
        }
    }

    private static void block(int[] state, int[] working, ByteBuf out, int outIndex) {
        System.arraycopy(state, 0, working, 0, state.length);
        rounds(working);
        for (int i = 0; i < working.length; i++) {
            intToLittleEndian(working[i] + state[i], out, outIndex + (i << 2));
        }
    }

    private static void rounds(int[] x) {
        for (int i = 0; i < 10; i++) {
            quarterRound(x, 0, 4, 8, 12);
            quarterRound(x, 1, 5, 9, 13);
            quarterRound(x, 2, 6, 10, 14);
            quarterRound(x, 3, 7, 11, 15);
            quarterRound(x, 0, 5, 10, 15);
            quarterRound(x, 1, 6, 11, 12);
            quarterRound(x, 2, 7, 8, 13);
            quarterRound(x, 3, 4, 9, 14);
        }
    }

    private static void quarterRound(int[] x, int a, int b, int c, int d) {
        x[a] += x[b];
        x[d] = Integer.rotateLeft(x[d] ^ x[a], 16);
        x[c] += x[d];
        x[b] = Integer.rotateLeft(x[b] ^ x[c], 12);
        x[a] += x[b];
        x[d] = Integer.rotateLeft(x[d] ^ x[a], 8);
        x[c] += x[d];
        x[b] = Integer.rotateLeft(x[b] ^ x[c], 7);
    }

    private static int littleEndianToInt(byte[] b, int off) {
        return (b[off] & 0xFF)
                | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16)
                | (b[off + 3] << 24);
    }

    private static int littleEndianToInt(ByteBuf b, int off) {
        return (b.getByte(off) & 0xFF)
                | ((b.getByte(off + 1) & 0xFF) << 8)
                | ((b.getByte(off + 2) & 0xFF) << 16)
                | (b.getByte(off + 3) << 24);
    }

    public static void intToLittleEndian(int value, byte[] out, int off) {
        out[off] = (byte) value;
        out[off + 1] = (byte) (value >>> 8);
        out[off + 2] = (byte) (value >>> 16);
        out[off + 3] = (byte) (value >>> 24);
    }

    public static void intToLittleEndian(int value, ByteBuf out, int off) {
        out.setByte(off, value);
        out.setByte(off + 1, value >>> 8);
        out.setByte(off + 2, value >>> 16);
        out.setByte(off + 3, value >>> 24);
    }

    private static void checkKey(byte[] key, String name) {
        if (key == null || key.length != KEY_SIZE_BYTES) {
            throw new IllegalArgumentException(name + " requires a 256 bit key");
        }
    }

    private static void checkOut(byte[] out, int outIndex, int length) {
        if (out == null || outIndex < 0 || out.length - outIndex < length) {
            throw new IllegalArgumentException("Output too short");
        }
    }

    private static void checkBuffer(ByteBuf buf, int index, int length, String message) {
        if (buf == null || index < 0 || buf.capacity() - index < length) {
            throw new IllegalArgumentException(message);
        }
    }
}
