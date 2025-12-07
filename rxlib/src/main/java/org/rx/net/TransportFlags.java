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

    CIPHER_READ(1 << 4),
    CIPHER_WRITE(1 << 5),
    CIPHER_BOTH(CIPHER_READ.value | CIPHER_WRITE.value),

    SERVER_HTTP_PSEUDO_READ(1 << 8),
    SERVER_HTTP_PSEUDO_WRITE(1 << 9),
    SERVER_HTTP_PSEUDO_BOTH(SERVER_HTTP_PSEUDO_READ.value | SERVER_HTTP_PSEUDO_WRITE.value),
    CLIENT_HTTP_PSEUDO_READ(1 << 10),
    CLIENT_HTTP_PSEUDO_WRITE(1 << 11),
    CLIENT_HTTP_PSEUDO_BOTH(CLIENT_HTTP_PSEUDO_READ.value | CLIENT_HTTP_PSEUDO_WRITE.value),

    SERVER_TLS(1 << 12),
    CLIENT_TLS(1 << 13),

    GFW(1 << 14);

    final int value;
}
