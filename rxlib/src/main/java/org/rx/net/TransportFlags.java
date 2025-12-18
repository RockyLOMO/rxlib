package org.rx.net;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.rx.bean.NEnum;

@RequiredArgsConstructor
@Getter
public enum TransportFlags implements NEnum<TransportFlags> {
    NONE(0),

    COMPRESS_READ(1),
    COMPRESS_WRITE(1 << 1),
    COMPRESS_BOTH(COMPRESS_READ.value | COMPRESS_WRITE.value),

    CIPHER_READ(1 << 2),
    CIPHER_WRITE(1 << 3),
    CIPHER_BOTH(CIPHER_READ.value | CIPHER_WRITE.value),

    HTTP_PSEUDO_READ(1 << 8),
    HTTP_PSEUDO_WRITE(1 << 9),
    HTTP_PSEUDO_BOTH(HTTP_PSEUDO_READ.value | HTTP_PSEUDO_WRITE.value),

    TLS(1 << 12),

    GFW(1 << 14);

    final int value;
}
