package org.rx.net.socks.encryption;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.rx.net.socks.encryption.impl.Aes128GcmByteBufCrypto;
import org.rx.net.socks.encryption.impl.Aes256GcmByteBufCrypto;
import org.rx.net.socks.encryption.impl.ChaCha20Poly1305ByteBufCrypto;

@RequiredArgsConstructor
@Getter
public enum CipherKind {
    AES_128_GCM("aes-128-gcm", Aes128GcmByteBufCrypto.class),
    AES_256_GCM("aes-256-gcm", Aes256GcmByteBufCrypto.class),
    CHACHA20_IETF_POLY1305("chacha20-ietf-poly1305", ChaCha20Poly1305ByteBufCrypto.class);

    private static final CipherKind[] METHODS = values();
    final String cipherName;
    final Class<?> type;

    public static ICrypto newInstance(String name, String password) {
        return forName(name).newAead(password);
    }

    static CipherKind forName(String name) {
        for (CipherKind kind : METHODS) {
            if (kind.cipherName.equalsIgnoreCase(name)) return kind;
        }
        throw new IllegalArgumentException("Unsupported cipher: " + name);
    }

    AeadCrypto newAead(String password) {
        switch (this) {
            case AES_128_GCM: return new Aes128GcmByteBufCrypto(cipherName, password);
            case AES_256_GCM: return new Aes256GcmByteBufCrypto(cipherName, password);
            case CHACHA20_IETF_POLY1305: return new ChaCha20Poly1305ByteBufCrypto(cipherName, password);
            default: throw new AssertionError(this);
        }
    }
}
