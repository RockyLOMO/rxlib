package org.rx.net.socks.encryption.impl;

import org.rx.net.socks.encryption.ShadowSocksKey;

public class Aes256GcmByteBufCrypto extends AesGcmByteBufCrypto {
    public static final String AEAD_AES_256_GCM = "aes-256-gcm";
    private static final int KEY_LENGTH = 32;
    private static final int SALT_LENGTH = 32;

    public Aes256GcmByteBufCrypto(String name, String password) {
        this(name, password, true);
    }

    public Aes256GcmByteBufCrypto(String name, String password, boolean directBuf) {
        this(name, new ShadowSocksKey(password, KEY_LENGTH), directBuf);
    }

    public Aes256GcmByteBufCrypto(String name, ShadowSocksKey masterKey, boolean directBuf) {
        super(name, AEAD_AES_256_GCM, masterKey, KEY_LENGTH, SALT_LENGTH, directBuf);
    }
}
