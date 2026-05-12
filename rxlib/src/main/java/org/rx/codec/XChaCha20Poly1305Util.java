package org.rx.codec;

import org.rx.core.Linq;
import org.rx.core.RxConfig;

import java.math.BigInteger;
import java.util.Arrays;

public class XChaCha20Poly1305Util {
    private static final int KEY_LEN = ChaCha20Engine.KEY_SIZE_BYTES;
    private static final int NONCE_LEN = ChaCha20Engine.X_NONCE_SIZE_BYTES;
    private static final int TAG_LEN = 16;
    private static final BigInteger P = BigInteger.ONE.shiftLeft(130).subtract(BigInteger.valueOf(5));
    private static final BigInteger MASK_128 = BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE);

    public static byte[] encrypt(byte[] key, byte[] plaintext) {
        checkKey(key);
        byte[] nonce = CodecUtil.secureRandomBytes(NONCE_LEN);
        byte[] subKey = ChaCha20Engine.xChaCha20SubKey(key, nonce);
        byte[] nonce12 = ChaCha20Engine.xChaCha20IetfNonce(nonce);
        byte[] ciphertext = new byte[plaintext.length];
        ChaCha20Engine.xor(subKey, nonce12, 1, plaintext, 0, plaintext.length, ciphertext, 0);

        byte[] tag = doMac(subKey, nonce12, ciphertext);
        byte[] output = new byte[NONCE_LEN + ciphertext.length + TAG_LEN];
        System.arraycopy(nonce, 0, output, 0, NONCE_LEN);
        System.arraycopy(ciphertext, 0, output, NONCE_LEN, ciphertext.length);
        System.arraycopy(tag, 0, output, NONCE_LEN + ciphertext.length, TAG_LEN);
        return output;
    }

    public static byte[] decrypt(byte[] key, byte[] input) {
        checkKey(key);
        if (input == null || input.length < NONCE_LEN + TAG_LEN) {
            throw new IllegalArgumentException("Input too short");
        }

        byte[] nonce = Arrays.copyOfRange(input, 0, NONCE_LEN);
        byte[] subKey = ChaCha20Engine.xChaCha20SubKey(key, nonce);
        byte[] nonce12 = ChaCha20Engine.xChaCha20IetfNonce(nonce);
        int cipherLen = input.length - NONCE_LEN - TAG_LEN;
        byte[] ciphertext = Arrays.copyOfRange(input, NONCE_LEN, NONCE_LEN + cipherLen);
        byte[] tag = Arrays.copyOfRange(input, NONCE_LEN + cipherLen, input.length);
        byte[] computedTag = doMac(subKey, nonce12, ciphertext);
        if (!equalsConstantTime(tag, computedTag)) {
            throw new SecurityException("Tag mismatch");
        }

        byte[] plaintext = new byte[cipherLen];
        ChaCha20Engine.xor(subKey, nonce12, 1, ciphertext, 0, cipherLen, plaintext, 0);
        return plaintext;
    }

    public static byte[] generateKey() {
        return CodecUtil.secureRandomBytes(KEY_LEN);
    }

    public static byte[] encrypt(byte[] plaintext) {
        return encrypt(Linq.from(RxConfig.INSTANCE.getNet().getCiphers()).where(p -> p.startsWith("2,"))
                .select(p -> CodecUtil.convertFromBase64(p.substring(2))).first(), plaintext);
    }

    public static byte[] decrypt(byte[] input) {
        return decrypt(Linq.from(RxConfig.INSTANCE.getNet().getCiphers()).where(p -> p.startsWith("2,"))
                .select(p -> CodecUtil.convertFromBase64(p.substring(2))).first(), input);
    }

    private static void checkKey(byte[] key) {
        if (key == null || key.length != KEY_LEN) {
            throw new IllegalArgumentException("Key must be " + KEY_LEN + " bytes");
        }
    }

    private static byte[] doMac(byte[] subKey, byte[] nonce12, byte[] ciphertext) {
        byte[] firstBlock = new byte[ChaCha20Engine.BLOCK_SIZE_BYTES];
        ChaCha20Engine.block(subKey, nonce12, 0, firstBlock);
        byte[] macKey = Arrays.copyOf(firstBlock, 32);
        byte[] macData = macData(ciphertext);
        return poly1305(macKey, macData);
    }

    private static byte[] macData(byte[] ciphertext) {
        int paddedCipherLen = paddedLength(ciphertext.length);
        byte[] data = new byte[paddedCipherLen + 16];
        System.arraycopy(ciphertext, 0, data, 0, ciphertext.length);
        writeLongLE(0, data, paddedCipherLen);
        writeLongLE(ciphertext.length, data, paddedCipherLen + 8);
        return data;
    }

    private static int paddedLength(int len) {
        return (len + 15) & ~15;
    }

    private static byte[] poly1305(byte[] key, byte[] msg) {
        byte[] rBytes = Arrays.copyOfRange(key, 0, 16);
        rBytes[3] &= 15;
        rBytes[7] &= 15;
        rBytes[11] &= 15;
        rBytes[15] &= 15;
        rBytes[4] &= 252;
        rBytes[8] &= 252;
        rBytes[12] &= 252;

        BigInteger r = littleEndian(rBytes, 0, 16);
        BigInteger s = littleEndian(key, 16, 16);
        BigInteger acc = BigInteger.ZERO;
        for (int i = 0; i < msg.length; i += 16) {
            int len = Math.min(16, msg.length - i);
            byte[] block = new byte[len + 1];
            System.arraycopy(msg, i, block, 0, len);
            block[len] = 1;
            acc = acc.add(littleEndian(block, 0, block.length)).multiply(r).mod(P);
        }
        return toLittleEndian(acc.add(s).and(MASK_128), TAG_LEN);
    }

    private static BigInteger littleEndian(byte[] b, int off, int len) {
        byte[] be = new byte[len + 1];
        for (int i = 0; i < len; i++) {
            be[len - i] = b[off + i];
        }
        return new BigInteger(be);
    }

    private static byte[] toLittleEndian(BigInteger value, int len) {
        byte[] be = value.toByteArray();
        byte[] out = new byte[len];
        for (int i = 0; i < len; i++) {
            int src = be.length - 1 - i;
            if (src >= 0) {
                out[i] = be[src];
            }
        }
        return out;
    }

    private static void writeLongLE(long value, byte[] out, int off) {
        for (int i = 0; i < 8; i++) {
            out[off + i] = (byte) (value >>> (i << 3));
        }
    }

    private static boolean equalsConstantTime(byte[] a, byte[] b) {
        if (a.length != b.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length; i++) {
            diff |= a[i] ^ b[i];
        }
        return diff == 0;
    }
}
