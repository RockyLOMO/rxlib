package org.rx.net.socks.encryption;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.rx.core.Strings;
import org.rx.net.socks.encryption.impl.AesGcmCrypto;
//import org.rx.net.shadowsocks.encryption.impl.ChaCha20Poly1305Crypto;

@RequiredArgsConstructor
@Getter
public enum CipherKind {
    //    CHACHA20_POLY1305("chacha20-ietf-poly1305", ChaCha20Poly1305Crypto.class),
    AES_128_GCM("aes-128-gcm", AesGcmCrypto.class),
    AES_192_GCM("aes-192-gcm", AesGcmCrypto.class),
    AES_256_GCM("aes-256-gcm", AesGcmCrypto.class);

    final String cipherName;
    final Class<?> type;

    public static ICrypto newInstance(String name, String password) {
        if (Strings.hashEquals(AES_256_GCM.cipherName, name)
                || Strings.hashEquals(AES_192_GCM.cipherName, name)
                || Strings.hashEquals(AES_128_GCM.cipherName, name)) {
            return new AesGcmCrypto(name, password);
        }
        throw new IllegalArgumentException("Unsupported cipher: " + name);
    }
}
