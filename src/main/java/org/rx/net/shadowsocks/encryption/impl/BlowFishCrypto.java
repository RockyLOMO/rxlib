package org.rx.net.shadowsocks.encryption.impl;

import lombok.SneakyThrows;
import org.bouncycastle.crypto.StreamBlockCipher;
import org.bouncycastle.crypto.engines.BlowfishEngine;
import org.bouncycastle.crypto.modes.CFBBlockCipher;
import org.rx.net.shadowsocks.encryption.CryptoSteamBase;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.security.InvalidAlgorithmParameterException;
import java.util.HashMap;
import java.util.Map;

public class BlowFishCrypto extends CryptoSteamBase {
    public final static String CIPHER_BLOWFISH_CFB = "bf-cfb";

    public static Map<String, String> getCiphers() {
        Map<String, String> ciphers = new HashMap<>();
        ciphers.put(CIPHER_BLOWFISH_CFB, BlowFishCrypto.class.getName());
        return ciphers;
    }

    public BlowFishCrypto(String name, String password) {
        super(name, password);
    }

    @Override
    public int getIVLength() {
        return 8;
    }

    @Override
    public int getKeyLength() {
        return 16;
    }

    @Override
    protected SecretKey getKey() {
        return new SecretKeySpec(_ssKey.getEncoded(), "AES");
    }

    @SneakyThrows
    @Override
    protected StreamBlockCipher getCipher(boolean isEncrypted) {
        if (!_name.equals(CIPHER_BLOWFISH_CFB)) {
            throw new InvalidAlgorithmParameterException(_name);
        }
        return new CFBBlockCipher(new BlowfishEngine(), getIVLength() * 8);
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
        int noBytesProcessed = decCipher.processBytes(data, 0, data.length, buffer, 0);
        stream.write(buffer, 0, noBytesProcessed);
    }
}
