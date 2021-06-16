package org.rx.net.shadowsocks.encryption.impl;

import org.bouncycastle.crypto.CipherParameters;
import org.bouncycastle.crypto.StreamCipher;
import org.bouncycastle.crypto.engines.RC4Engine;
import org.bouncycastle.crypto.params.KeyParameter;
import org.rx.net.shadowsocks.encryption.CryptoSteamBase;
import org.rx.security.MD5Util;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;

public class Rc4Md5Crypto extends CryptoSteamBase {
    public static String CIPHER_RC4_MD5 = "rc4-md5";

    public static Map<String, String> getCiphers() {
        Map<String, String> ciphers = new HashMap<>();
        ciphers.put(CIPHER_RC4_MD5, Rc4Md5Crypto.class.getName());
        return ciphers;
    }

    public Rc4Md5Crypto(String name, String password) {
        super(name, password);
    }

    @Override
    protected int getIVLength() {
        return 16;
    }

    @Override
    protected int getKeyLength() {
        return 16;
    }

    @Override
    protected SecretKey getKey() {
        return new SecretKeySpec(_ssKey.getEncoded(), "AES");
    }

    @Override
    protected StreamCipher getCipher(boolean isEncrypted) {
        return new RC4Engine();
    }

    @Override
    protected CipherParameters getCipherParameters(byte[] iv) {
        byte[] bts = new byte[_keyLength + _ivLength];
        System.arraycopy(_key.getEncoded(), 0, bts, 0, _keyLength);
        System.arraycopy(iv, 0, bts, _keyLength, _ivLength);
        return new KeyParameter(MD5Util.md5(bts));
    }

    @Override
    protected void _encrypt(byte[] data, ByteArrayOutputStream stream) {
        int noBytesProcessed;
        byte[] buffer = new byte[data.length];

        noBytesProcessed = encCipher.processBytes(data, 0, data.length, buffer, 0);
        stream.write(buffer, 0, noBytesProcessed);
    }

    @Override
    protected void _decrypt(byte[] data, ByteArrayOutputStream stream) {
        int noBytesProcessed;
        byte[] buffer = new byte[data.length];

        noBytesProcessed = decCipher.processBytes(data, 0, data.length, buffer, 0);
        stream.write(buffer, 0, noBytesProcessed);
    }
}
