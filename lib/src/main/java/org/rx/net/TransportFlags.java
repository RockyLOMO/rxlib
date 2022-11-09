package org.rx.net;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.rx.bean.NEnum;

@RequiredArgsConstructor
@Getter
public enum TransportFlags implements NEnum<TransportFlags> {
    NONE(0),
    FRONTEND_SSL(1),
    FRONTEND_AES(1 << 1),
    FRONTEND_COMPRESS(1 << 2),
    BACKEND_SSL(1 << 3),
    BACKEND_AES(1 << 4),
    BACKEND_COMPRESS(1 << 5),
    FRONTEND_AES_COMBO(FRONTEND_AES.value | FRONTEND_COMPRESS.value),
    BACKEND_AES_COMBO(BACKEND_AES.value | BACKEND_COMPRESS.value),
    BOTH_AES_COMBO(FRONTEND_AES_COMBO.value | BACKEND_AES_COMBO.value),
    FRONTEND_SSL_COMBO(FRONTEND_SSL.value | FRONTEND_COMPRESS.value),
    BACKEND_SSL_COMBO(BACKEND_SSL.value | BACKEND_COMPRESS.value),
    BOTH_SSL_COMBO(FRONTEND_SSL_COMBO.value | BACKEND_SSL_COMBO.value);

    final int value;
}
