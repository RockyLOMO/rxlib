package org.rx.net.socks.encryption;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.rx.core.Strings;
import org.rx.net.socks.encryption.impl.Aes256GcmByteBufCrypto;
import org.rx.net.socks.encryption.impl.ChaCha20Poly1305ByteBufCrypto;

@RequiredArgsConstructor
@Getter
public enum CipherKind {
    AES_256_GCM("aes-256-gcm", Aes256GcmByteBufCrypto.class),
    CHACHA20_IETF_POLY1305("chacha20-ietf-poly1305", ChaCha20Poly1305ByteBufCrypto.class);

    final String cipherName;
    final Class<?> type;

    public static ICrypto newInstance(String name, String password) {
        if (Strings.hashEquals(AES_256_GCM.cipherName, name)) {
            return new Aes256GcmByteBufCrypto(name, password);
        }
        if (Strings.hashEquals(CHACHA20_IETF_POLY1305.cipherName, name)) {
            return new ChaCha20Poly1305ByteBufCrypto(name, password);
        }
        throw new IllegalArgumentException("Unsupported cipher: " + name);
    }
}
