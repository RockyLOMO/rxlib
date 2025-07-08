package org.rx.net;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.rx.bean.NEnum;

@RequiredArgsConstructor
@Getter
public enum TransportFlags implements NEnum<TransportFlags> {
    NONE(0),
    SERVER_COMPRESS_READ(1),
    SERVER_COMPRESS_WRITE(1 << 1),
    SERVER_COMPRESS_BOTH(SERVER_COMPRESS_READ.value | SERVER_COMPRESS_WRITE.value),
    CLIENT_COMPRESS_READ(1 << 2),
    CLIENT_COMPRESS_WRITE(1 << 3),
    CLIENT_COMPRESS_BOTH(CLIENT_COMPRESS_READ.value | CLIENT_COMPRESS_WRITE.value),
    SERVER_CIPHER_READ(1 << 4),
    SERVER_CIPHER_WRITE(1 << 5),
    SERVER_CIPHER_BOTH(SERVER_CIPHER_READ.value | SERVER_CIPHER_WRITE.value),
    CLIENT_CIPHER_READ(1 << 6),
    CLIENT_CIPHER_WRITE(1 << 7),
    CLIENT_CIPHER_BOTH(CLIENT_CIPHER_READ.value | CLIENT_CIPHER_WRITE.value),
    FRONTEND_SSL(1 << 8),
    BACKEND_SSL(1 << 9),
    FRONTEND_AES_COMBO(SERVER_COMPRESS_BOTH.value | SERVER_CIPHER_BOTH.value),
    BACKEND_AES_COMBO(CLIENT_COMPRESS_BOTH.value | CLIENT_CIPHER_BOTH.value),
    BOTH_AES_COMBO(FRONTEND_AES_COMBO.value | BACKEND_AES_COMBO.value)
    ;

    final int value;
}
