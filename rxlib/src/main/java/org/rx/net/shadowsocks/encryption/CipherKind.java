package org.rx.net.shadowsocks.encryption;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.rx.net.shadowsocks.encryption.impl.*;

@RequiredArgsConstructor
@Getter
public enum CipherKind {
    CHACHA20_POLY1305("chacha20-ietf-poly1305", ChaCha20Poly1305Crypto.class),
    AES_128_GCM("aes-128-gcm", AesGcmCrypto.class),
    AES_192_GCM("aes-192-gcm", AesGcmCrypto.class),
    AES_256_GCM("aes-256-gcm", AesGcmCrypto.class);

    final String cipherName;
    final Class<?> type;
}
