package org.rx.net.socks.encryption.impl;

public class Aes128GcmByteBufCrypto extends AesGcmByteBufCrypto {
    public static final String AEAD_AES_128_GCM = "aes-128-gcm";
    private static final int KEY_LENGTH = 16;
    private static final int SALT_LENGTH = 16;

    public Aes128GcmByteBufCrypto(String name, String password) {
        this(name, password, true);
    }

    public Aes128GcmByteBufCrypto(String name, String password, boolean directBuf) {
        super(name, AEAD_AES_128_GCM, password, KEY_LENGTH, SALT_LENGTH, directBuf);
    }
}
