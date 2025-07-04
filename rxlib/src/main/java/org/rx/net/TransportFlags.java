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
    SERVER_AES_READ(1 << 4),
    SERVER_AES_WRITE(1 << 5),
    SERVER_AES_BOTH(SERVER_AES_READ.value | SERVER_AES_WRITE.value),
    CLIENT_AES_READ(1 << 6),
    CLIENT_AES_WRITE(1 << 7),
    CLIENT_AES_BOTH(CLIENT_AES_READ.value | CLIENT_AES_WRITE.value),
    FRONTEND_SSL(1 << 8),
    //    FRONTEND_AES(1 << 5),
    BACKEND_SSL(1 << 9),
    //    BACKEND_AES(1 << 7),
    FRONTEND_AES_COMBO(SERVER_AES_BOTH.value | SERVER_COMPRESS_BOTH.value),
    BACKEND_AES_COMBO(CLIENT_AES_BOTH.value | CLIENT_COMPRESS_BOTH.value),
    BOTH_AES_COMBO(FRONTEND_AES_COMBO.value | BACKEND_AES_COMBO.value),
    FRONTEND_SSL_COMBO(FRONTEND_SSL.value | SERVER_COMPRESS_BOTH.value),
    BACKEND_SSL_COMBO(BACKEND_SSL.value | CLIENT_COMPRESS_BOTH.value),
    BOTH_SSL_COMBO(FRONTEND_SSL_COMBO.value | BACKEND_SSL_COMBO.value);

    final int value;
}
