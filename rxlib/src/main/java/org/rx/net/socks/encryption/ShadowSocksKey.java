package org.rx.net.socks.encryption;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Slf4j
public class ShadowSocksKey implements SecretKey {
    private static final long serialVersionUID = 1L;
    private final static int KEY_LENGTH = 32;
    private final byte[] _key;
    private final int _length;

    public ShadowSocksKey(String password) {
        _length = KEY_LENGTH;
        _key = init(password);
    }

    public ShadowSocksKey(String password, int length) {
        _length = length;
        _key = init(password);
    }

    @SneakyThrows
    private byte[] init(String password) {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] keys = new byte[KEY_LENGTH];
        byte[] temp = null;
        byte[] hash = null;
        byte[] passwordBytes = password.getBytes(StandardCharsets.UTF_8);
        int i = 0;

        while (i < keys.length) {
            if (i == 0) {
                hash = md.digest(passwordBytes);
                temp = new byte[passwordBytes.length + hash.length];
            } else {
                System.arraycopy(hash, 0, temp, 0, hash.length);
                System.arraycopy(passwordBytes, 0, temp, hash.length, passwordBytes.length);
                hash = md.digest(temp);
            }
            System.arraycopy(hash, 0, keys, i, hash.length);
            i += hash.length;
        }

        if (_length != KEY_LENGTH) {
            byte[] keysl = new byte[_length];
            System.arraycopy(keys, 0, keysl, 0, _length);
            return keysl;
        }
        return keys;
    }

    @Override
    public String getAlgorithm() {
        return "shadowsocks";
    }

    @Override
    public String getFormat() {
        return "RAW";
    }

    @Override
    public byte[] getEncoded() {
        return _key;
    }
}
