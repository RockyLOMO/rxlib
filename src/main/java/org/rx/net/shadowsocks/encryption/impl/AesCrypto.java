package org.rx.net.shadowsocks.encryption.impl;

import lombok.SneakyThrows;
import org.bouncycastle.crypto.StreamBlockCipher;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.modes.CFBBlockCipher;
import org.bouncycastle.crypto.modes.OFBBlockCipher;
import org.rx.net.shadowsocks.encryption.CryptoSteamBase;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.security.InvalidAlgorithmParameterException;
import java.util.HashMap;
import java.util.Map;

public class AesCrypto extends CryptoSteamBase {
    public final static String CIPHER_AES_128_CFB = "aes-128-cfb";
    public final static String CIPHER_AES_192_CFB = "aes-192-cfb";
    public final static String CIPHER_AES_256_CFB = "aes-256-cfb";
    public final static String CIPHER_AES_128_OFB = "aes-128-ofb";
    public final static String CIPHER_AES_192_OFB = "aes-192-ofb";
    public final static String CIPHER_AES_256_OFB = "aes-256-ofb";

    public static Map<String, String> getCiphers() {
        Map<String, String> ciphers = new HashMap<>();
        ciphers.put(CIPHER_AES_128_CFB, AesCrypto.class.getName());
        ciphers.put(CIPHER_AES_192_CFB, AesCrypto.class.getName());
        ciphers.put(CIPHER_AES_256_CFB, AesCrypto.class.getName());
        ciphers.put(CIPHER_AES_128_OFB, AesCrypto.class.getName());
        ciphers.put(CIPHER_AES_192_OFB, AesCrypto.class.getName());
        ciphers.put(CIPHER_AES_256_OFB, AesCrypto.class.getName());
        return ciphers;
    }

    public AesCrypto(String name, String password) {
        super(name, password);
    }

    @Override
    public int getIVLength() {
        return 16;
    }

    @Override
    public int getKeyLength() {
        switch (_name) {
            case CIPHER_AES_128_CFB:
            case CIPHER_AES_128_OFB:
                return 16;
            case CIPHER_AES_192_CFB:
            case CIPHER_AES_192_OFB:
                return 24;
            case CIPHER_AES_256_CFB:
            case CIPHER_AES_256_OFB:
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
    protected StreamBlockCipher getCipher(boolean isEncrypted) {
        AESEngine engine = new AESEngine();
        StreamBlockCipher cipher;
        switch (_name) {
            case CIPHER_AES_128_CFB:
                cipher = new CFBBlockCipher(engine, getIVLength() * 8);
                break;
            case CIPHER_AES_192_CFB:
                cipher = new CFBBlockCipher(engine, getIVLength() * 8);
                break;
            case CIPHER_AES_256_CFB:
                cipher = new CFBBlockCipher(engine, getIVLength() * 8);
                break;
            case CIPHER_AES_128_OFB:
                cipher = new OFBBlockCipher(engine, getIVLength() * 8);
                break;
            case CIPHER_AES_192_OFB:
                cipher = new OFBBlockCipher(engine, getIVLength() * 8);
                break;
            case CIPHER_AES_256_OFB:
                cipher = new OFBBlockCipher(engine, getIVLength() * 8);
                break;
            default:
                throw new InvalidAlgorithmParameterException(_name);
        }
        return cipher;
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
