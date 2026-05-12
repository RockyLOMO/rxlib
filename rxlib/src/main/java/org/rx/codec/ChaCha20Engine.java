package org.rx.codec;

public final class ChaCha20Engine {
    public static final int BLOCK_SIZE_BYTES = 64;
    public static final int IETF_NONCE_SIZE_BYTES = 12;
    public static final int X_NONCE_SIZE_BYTES = 24;
    public static final int KEY_SIZE_BYTES = 32;
    private static final int[] SIGMA = {
            0x61707865, 0x3320646E, 0x79622D32, 0x6B206574
    };

    private ChaCha20Engine() {
    }

    public static byte[] xChaCha20SubKey(byte[] key, byte[] nonce24) {
        if (key == null || key.length != KEY_SIZE_BYTES) {
            throw new IllegalArgumentException("XChaCha20 requires a 256 bit key");
        }
        if (nonce24 == null || nonce24.length != X_NONCE_SIZE_BYTES) {
            throw new IllegalArgumentException("XChaCha20 requires a 192 bit nonce");
        }
        int[] state = new int[16];
        state[0] = SIGMA[0];
        state[1] = SIGMA[1];
        state[2] = SIGMA[2];
        state[3] = SIGMA[3];
        for (int i = 0; i < 8; i++) {
            state[4 + i] = littleEndianToInt(key, i << 2);
        }
        for (int i = 0; i < 4; i++) {
            state[12 + i] = littleEndianToInt(nonce24, i << 2);
        }

        rounds(state);
        byte[] out = new byte[KEY_SIZE_BYTES];
        intToLittleEndian(state[0], out, 0);
        intToLittleEndian(state[1], out, 4);
        intToLittleEndian(state[2], out, 8);
        intToLittleEndian(state[3], out, 12);
        intToLittleEndian(state[12], out, 16);
        intToLittleEndian(state[13], out, 20);
        intToLittleEndian(state[14], out, 24);
        intToLittleEndian(state[15], out, 28);
        return out;
    }

    public static byte[] xChaCha20IetfNonce(byte[] nonce24) {
        if (nonce24 == null || nonce24.length != X_NONCE_SIZE_BYTES) {
            throw new IllegalArgumentException("XChaCha20 requires a 192 bit nonce");
        }
        byte[] nonce12 = new byte[IETF_NONCE_SIZE_BYTES];
        System.arraycopy(nonce24, 16, nonce12, 4, 8);
        return nonce12;
    }

    public static void xor(byte[] key, byte[] nonce12, int counter, byte[] input, int inputOffset,
                    int length, byte[] output, int outputOffset) {
        byte[] block = new byte[BLOCK_SIZE_BYTES];
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

    public static void block(byte[] key, byte[] nonce12, int counter, byte[] out) {
        if (key == null || key.length != KEY_SIZE_BYTES) {
            throw new IllegalArgumentException("ChaCha20 requires a 256 bit key");
        }
        if (nonce12 == null || nonce12.length != IETF_NONCE_SIZE_BYTES) {
            throw new IllegalArgumentException("ChaCha20-IETF requires a 96 bit nonce");
        }
        int[] state = new int[16];
        int[] working = new int[16];
        state[0] = SIGMA[0];
        state[1] = SIGMA[1];
        state[2] = SIGMA[2];
        state[3] = SIGMA[3];
        for (int i = 0; i < 8; i++) {
            state[4 + i] = littleEndianToInt(key, i << 2);
        }
        state[12] = counter;
        state[13] = littleEndianToInt(nonce12, 0);
        state[14] = littleEndianToInt(nonce12, 4);
        state[15] = littleEndianToInt(nonce12, 8);

        System.arraycopy(state, 0, working, 0, state.length);
        rounds(working);
        for (int i = 0; i < working.length; i++) {
            intToLittleEndian(working[i] + state[i], out, i << 2);
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

    public static void intToLittleEndian(int value, byte[] out, int off) {
        out[off] = (byte) value;
        out[off + 1] = (byte) (value >>> 8);
        out[off + 2] = (byte) (value >>> 16);
        out[off + 3] = (byte) (value >>> 24);
    }
}
