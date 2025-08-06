package org.rx.codec;

import org.bouncycastle.crypto.macs.Poly1305;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersWithIV;
import org.rx.core.Linq;
import org.rx.core.RxConfig;

import java.security.SecureRandom;
import java.util.Arrays;

public class XChaCha20Poly1305Util {
    private static final int KEY_LEN = XChaCha20Engine.KEY_SIZE_BYTES; // 32 bytes for XChaCha20
    private static final int NONCE_LEN = XChaCha20Engine.NONCE_SIZE_BYTES; // 24 bytes for XChaCha20
    private static final int TAG_LEN = 16; // 16 bytes for Poly1305 MAC
    private static final SecureRandom RANDOM = new SecureRandom();

    public static byte[] encrypt(byte[] key, byte[] plaintext) {
        if (key == null || key.length != KEY_LEN) {
            throw new IllegalArgumentException("Key must be " + KEY_LEN + " bytes");
        }

        byte[] nonce = new byte[NONCE_LEN];
        RANDOM.nextBytes(nonce);
        ParametersWithIV parametersWithIV = new ParametersWithIV(new KeyParameter(key), nonce);

        XChaCha20Engine cipher = new XChaCha20Engine();
        cipher.init(true, parametersWithIV);
        byte[] ciphertext = new byte[plaintext.length];
        cipher.processBytes(plaintext, 0, plaintext.length, ciphertext, 0);

        byte[] tag = doMac(parametersWithIV, ciphertext);

        byte[] output = new byte[NONCE_LEN + ciphertext.length + TAG_LEN];
        System.arraycopy(nonce, 0, output, 0, NONCE_LEN);
        System.arraycopy(ciphertext, 0, output, NONCE_LEN, ciphertext.length);
        System.arraycopy(tag, 0, output, NONCE_LEN + ciphertext.length, TAG_LEN);
        return output;
    }

    private static byte[] doMac(ParametersWithIV parametersWithIV, byte[] ciphertext) {
        XChaCha20Engine macCipher = new XChaCha20Engine();
        macCipher.init(true, parametersWithIV);
        byte[] polyKey = new byte[32];
        macCipher.processBytes(new byte[32], 0, 32, polyKey, 0);

        Poly1305 mac = new Poly1305();
        mac.init(new KeyParameter(polyKey));
        mac.update(ciphertext, 0, ciphertext.length);
        byte[] tag = new byte[TAG_LEN];
        mac.doFinal(tag, 0);
        return tag;
    }

    public static byte[] decrypt(byte[] key, byte[] input) {
        if (key == null || key.length != KEY_LEN) {
            throw new IllegalArgumentException("Key must be " + KEY_LEN + " bytes");
        }
        if (input == null || input.length < NONCE_LEN + TAG_LEN) {
            throw new IllegalArgumentException("Input too short");
        }

        byte[] nonce = Arrays.copyOfRange(input, 0, NONCE_LEN);
        byte[] ciphertext = Arrays.copyOfRange(input, NONCE_LEN, input.length - TAG_LEN);
        byte[] tag = Arrays.copyOfRange(input, input.length - TAG_LEN, input.length);
        ParametersWithIV parametersWithIV = new ParametersWithIV(new KeyParameter(key), nonce);

        byte[] computedTag = doMac(parametersWithIV, ciphertext);
        if (!Arrays.equals(tag, computedTag)) {
            throw new SecurityException("Tag mismatch");
        }

        XChaCha20Engine cipher = new XChaCha20Engine();
        cipher.init(false, parametersWithIV);
        byte[] plaintext = new byte[ciphertext.length];
        cipher.processBytes(ciphertext, 0, ciphertext.length, plaintext, 0);
        return plaintext;
    }

    public static byte[] generateKey() {
        byte[] key = new byte[KEY_LEN];
        RANDOM.nextBytes(key);
        return key;
    }

    public static byte[] encrypt(byte[] plaintext) {
        return encrypt(Linq.from(RxConfig.INSTANCE.getNet().getCiphers()).where(p -> p.startsWith("2,"))
                .select(p -> CodecUtil.convertFromBase64(p.substring(2))).first(), plaintext);
    }

    public static byte[] decrypt(byte[] input) {
        return decrypt(Linq.from(RxConfig.INSTANCE.getNet().getCiphers()).where(p -> p.startsWith("2,"))
                .select(p -> CodecUtil.convertFromBase64(p.substring(2))).first(), input);
    }
}
