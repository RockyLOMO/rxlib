package org.rx.net.shadowsocks.encryption.impl;

import lombok.SneakyThrows;
import org.bouncycastle.crypto.StreamCipher;
import org.bouncycastle.crypto.engines.ChaCha7539Engine;
import org.bouncycastle.crypto.engines.ChaChaEngine;
import org.rx.net.shadowsocks.encryption.CryptoSteamBase;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.security.InvalidAlgorithmParameterException;
import java.util.HashMap;
import java.util.Map;

public class Chacha20Crypto extends CryptoSteamBase {
    public final static String CIPHER_CHACHA20 = "chacha20";
    public final static String CIPHER_CHACHA20_IETF = "chacha20-ietf";

    public static Map<String, String> getCiphers() {
        Map<String, String> ciphers = new HashMap<>();
        ciphers.put(CIPHER_CHACHA20, Chacha20Crypto.class.getName());
        ciphers.put(CIPHER_CHACHA20_IETF, Chacha20Crypto.class.getName());
        return ciphers;
    }

    public Chacha20Crypto(String name, String password) {
        super(name, password);
    }

    @Override
    public int getIVLength() {
        if (_name.equals(CIPHER_CHACHA20)) {
            return 8;
        } else if (_name.equals(CIPHER_CHACHA20_IETF)) {
            return 12;
        }
        return 0;
    }

    @Override
    public int getKeyLength() {
        if (_name.equals(CIPHER_CHACHA20) || _name.equals(CIPHER_CHACHA20_IETF)) {
            return 32;
        }
        return 0;
    }

    @Override
    protected SecretKey getKey() {
        return new SecretKeySpec(_ssKey.getEncoded(), "AES");
    }

    @SneakyThrows
    @Override
    protected StreamCipher getCipher(boolean isEncrypted) {
        if (_name.equals(CIPHER_CHACHA20)) {
            return new ChaChaEngine();
        } else if (_name.equals(CIPHER_CHACHA20_IETF)) {
            return new ChaCha7539Engine();
        }
        throw new InvalidAlgorithmParameterException(_name);
    }

    @Override
    protected void _encrypt(byte[] data, ByteArrayOutputStream stream) {
        byte[] buffer = new byte[data.length];
        int noBytesProcessed = encCipher.processBytes(data, 0, data.length, buffer, 0);
        stream.write(buffer, 0, noBytesProcessed);
    }

    @Override
    protected void _decrypt(byte[] data, ByteArrayOutputStream stream) {
        byte[] buffer = new byte[data.length];
        int BytesProcessedNum = decCipher.processBytes(data, 0, data.length, buffer, 0);
        stream.write(buffer, 0, BytesProcessedNum);
    }
}
