package org.rx.net.shadowsocks.encryption;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.rx.net.shadowsocks.encryption.impl.*;

@RequiredArgsConstructor
@Getter
public enum CipherName {
    RC4_MD5("rc4-md5", Rc4Md5Crypto.class),
    BLOWFISH_CFB("bf-cfb", BlowFishCrypto.class),
    CHACHA20("chacha20", ChaCha20Crypto.class),
    CHACHA20_IETF("chacha20-ietf", ChaCha20Crypto.class),
    AES_128_CFB("aes-128-cfb", AesCrypto.class),
    AES_192_CFB("aes-192-cfb", AesCrypto.class),
    AES_256_CFB("aes-256-cfb", AesCrypto.class),
    AES_128_OFB("aes-128-ofb", AesCrypto.class),
    AES_192_OFB("aes-192-ofb", AesCrypto.class),
    AES_256_OFB("aes-256-ofb", AesCrypto.class),
    CHA_CHA_20_POLY_1305("chacha20-ietf-poly1305", ChaCha20Poly1305Crypto.class),
    AES_128_GCM("aes-128-gcm", AesGcmCrypto.class),
    AES_256_GCM("aes-256-gcm", AesGcmCrypto.class);

    final String name;
    final Class<?> type;
}
